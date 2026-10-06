package com.kw.knowone.group.service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.kw.knowone.auth.entity.AppUser;
import com.kw.knowone.auth.repository.AuthRepository;
import com.kw.knowone.auth.service.AuthService;
import com.kw.knowone.common.idempotency.IdempotentResult;
import com.kw.knowone.common.idempotency.MutationResponse;
import com.kw.knowone.common.schedule.ScheduleMutationService;
import com.kw.knowone.common.web.ApiException;
import com.kw.knowone.common.web.DataResponse;
import com.kw.knowone.group.dto.GroupDtos;
import com.kw.knowone.group.dto.GroupDtos.Group;
import com.kw.knowone.group.dto.GroupDtos.Items;
import com.kw.knowone.group.dto.GroupDtos.JoinRequest;
import com.kw.knowone.group.dto.GroupDtos.Member;
import com.kw.knowone.group.dto.GroupDtos.PriorityItem;
import com.kw.knowone.group.dto.GroupDtos.PriorityRequest;
import com.kw.knowone.group.dto.GroupDtos.RecipientLookup;
import com.kw.knowone.group.dto.GroupDtos.UserRef;
import com.kw.knowone.group.entity.CareGroup;
import com.kw.knowone.group.entity.GroupMember;
import com.kw.knowone.group.repository.GroupEventRepository;
import com.kw.knowone.group.repository.GroupRepository;

@Service
public class GroupService {
    private final GroupRepository repository;
    private final GroupEventRepository eventRepository;
    private final AuthRepository authRepository;
    private final AuthService authService;
    private final ScheduleMutationService scheduleMutations;
    private final RecipientLookupRateLimiter rateLimiter;
    private final Clock clock;

    public GroupService(GroupRepository repository, GroupEventRepository eventRepository,
            AuthRepository authRepository, AuthService authService, ScheduleMutationService scheduleMutations,
            RecipientLookupRateLimiter rateLimiter, Clock clock) {
        this.repository = repository;
        this.eventRepository = eventRepository;
        this.authRepository = authRepository;
        this.authService = authService;
        this.scheduleMutations = scheduleMutations;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    public Items<Group> groups(UUID userId) {
        return new Items<>(repository.findGroupsForActiveMember(userId).stream()
                .map(group -> toGroup(group, requireActiveMembership(group.id(), userId))).toList());
    }

    public Group group(UUID groupId, UUID userId) {
        CareGroup group = requireGroup(groupId);
        return toGroup(group, requireActiveMembership(groupId, userId));
    }

    public RecipientLookup lookup(UUID userId, String phoneNumber) {
        authService.requireDemoMode();
        requireDemoUser(userId);
        rateLimiter.consume(userId);
        CareGroup group = repository.findDemoRecipientByPhone(phoneNumber)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                        "등록된 돌봄 대상을 찾을 수 없습니다."));
        return new RecipientLookup(group.recipientUserId(), group.recipientDisplayName(), group.id());
    }

    public Items<Member> members(UUID groupId, UUID userId) {
        requireGroup(groupId);
        requireActiveMembership(groupId, userId);
        return new Items<>(repository.findActiveMembers(groupId).stream().map(this::toMember).toList());
    }

    public IdempotentResult join(UUID groupId, UUID userId, JoinRequest request, String idempotencyKey,
            UUID requestId) {
        authService.requireDemoMode();
        return scheduleMutations.execute(userId, "G04:" + groupId, idempotencyKey, request,
                () -> validateJoinAuthorization(groupId, userId, request),
                () -> doJoin(groupId, userId, requestId));
    }

    public IdempotentResult updatePriorities(UUID groupId, UUID userId, PriorityRequest request,
            String idempotencyKey, UUID requestId) {
        validateNoDuplicateMembers(request.members());
        return scheduleMutations.execute(userId, "G06:" + groupId, idempotencyKey, request,
                () -> validateGroupMutationAuthorization(groupId, userId),
                () -> doUpdatePriorities(groupId, userId, request, requestId));
    }

    private void validateJoinAuthorization(UUID groupId, UUID userId, JoinRequest request) {
        AppUser user = requireDemoUser(userId);
        CareGroup group = requireGroup(groupId);
        if (!"ACTIVE".equals(group.status())) throw invalidState("보관된 공동체에는 참여할 수 없습니다.");
        if (group.recipientUserId().equals(user.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "돌봄 대상 계정은 보호자로 참여할 수 없습니다.");
        }
        if (!group.recipientUserId().equals(request.recipientUserId())
                || !group.recipientDisplayName().equals(request.confirmedName())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "돌봄 대상 확인 정보가 일치하지 않습니다.");
        }
    }

    private MutationResponse doJoin(UUID groupId, UUID userId, UUID requestId) {
        GroupMember existing = repository.findMembership(groupId, userId).orElse(null);
        if (existing != null && "ACTIVE".equals(existing.status())) {
            return new MutationResponse(HttpStatus.OK.value(), DataResponse.of(toMember(existing)));
        }
        Instant now = clock.instant();
        GroupMember joined;
        Object before = null;
        if (existing == null) {
            joined = repository.insertCaregiver(UUID.randomUUID(), groupId, userId, 2);
        } else {
            if (!"CAREGIVER".equals(existing.role()) || !"LEFT".equals(existing.status())) {
                throw invalidState("현재 멤버십 상태에서는 다시 참여할 수 없습니다.");
            }
            before = eventMember(existing);
            joined = repository.reactivate(existing, now);
        }
        eventRepository.audit(groupId, userId, existing == null ? "GROUP_MEMBER_JOINED" : "GROUP_MEMBER_REJOINED",
                "GROUP_MEMBER", joined.id(), before, eventMember(joined), requestId);
        eventRepository.notification(groupId, "MEMBER_JOINED", "member-joined:" + joined.id() + ":" + joined.version(),
                Map.of("schemaVersion", 1, "memberId", joined.id(), "userId", userId), now);
        return new MutationResponse(HttpStatus.CREATED.value(), DataResponse.of(toMember(joined)));
    }

    private void validateGroupMutationAuthorization(UUID groupId, UUID userId) {
        CareGroup group = requireGroup(groupId);
        requireActiveMembership(groupId, userId);
        if (!"ACTIVE".equals(group.status())) throw invalidState("보관된 공동체는 변경할 수 없습니다.");
    }

    private MutationResponse doUpdatePriorities(UUID groupId, UUID userId, PriorityRequest request, UUID requestId) {
        List<GroupMember> targets = request.members().stream().map(item -> validatedTarget(groupId, item)).toList();
        java.util.ArrayList<Member> updated = new java.util.ArrayList<>();
        Instant now = clock.instant();
        for (int index = 0; index < targets.size(); index++) {
            GroupMember before = targets.get(index);
            PriorityItem requested = request.members().get(index);
            GroupMember after = repository.updatePriority(before, requested.priority());
            eventRepository.audit(groupId, userId, "MEMBER_PRIORITY_CHANGED", "GROUP_MEMBER", after.id(),
                    eventMember(before), eventMember(after), requestId);
            updated.add(toMember(after));
        }
        eventRepository.notification(groupId, "MEMBER_PRIORITY_CHANGED", "member-priority:" + requestId,
                Map.of("schemaVersion", 1, "memberIds", updated.stream().map(Member::id).toList()), now);
        return new MutationResponse(HttpStatus.OK.value(), DataResponse.of(new Items<>(updated)));
    }

    private GroupMember validatedTarget(UUID groupId, PriorityItem item) {
        GroupMember member = repository.findMemberById(item.memberId())
                .orElseThrow(() -> invalidState("변경할 구성원을 찾을 수 없습니다."));
        if (!member.groupId().equals(groupId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_MEMBER", "다른 공동체의 구성원은 변경할 수 없습니다.");
        }
        if (!"ACTIVE".equals(member.status()) || !"CAREGIVER".equals(member.role())) {
            throw invalidState("ACTIVE 보호자의 우선순위만 변경할 수 있습니다.");
        }
        if (member.version() != item.expectedVersion()) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "구성원 정보가 변경되었습니다.",
                    Map.of("memberId", member.id(), "currentVersion", member.version()));
        }
        return member;
    }

    private void validateNoDuplicateMembers(List<PriorityItem> members) {
        Set<UUID> ids = new HashSet<>();
        for (PriorityItem member : members) {
            if (!ids.add(member.memberId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "구성원 ID를 중복해서 보낼 수 없습니다.");
            }
        }
    }

    private CareGroup requireGroup(UUID groupId) {
        return repository.findGroup(groupId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "공동체를 찾을 수 없습니다."));
    }

    private GroupMember requireActiveMembership(UUID groupId, UUID userId) {
        return repository.findActiveMembership(groupId, userId).orElseThrow(() ->
                new ApiException(HttpStatus.FORBIDDEN, "NOT_MEMBER", "공동체의 ACTIVE 구성원이 아닙니다."));
    }

    private AppUser requireDemoUser(UUID userId) {
        AppUser user = authRepository.findActiveById(userId).orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증 정보가 올바르지 않습니다."));
        if (!"DEMO".equals(user.accountType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEMO_ONLY", "시연 계정만 사용할 수 있습니다.");
        }
        return user;
    }

    private Group toGroup(CareGroup group, GroupMember membership) {
        return new Group(group.id(), group.name(),
                new UserRef(group.recipientUserId(), group.recipientDisplayName()), group.status(),
                toMember(membership), group.version());
    }

    private Member toMember(GroupMember member) {
        return new Member(member.id(), new UserRef(member.userId(), member.displayName()), member.role(),
                member.priority(), member.status(), member.version());
    }

    private Map<String, Object> eventMember(GroupMember member) {
        return Map.of("id", member.id(), "userId", member.userId(), "role", member.role(),
                "priority", member.priority(), "status", member.status(), "version", member.version());
    }

    private ApiException invalidState(String message) {
        return new ApiException(HttpStatus.CONFLICT, "INVALID_STATE", message);
    }
}
