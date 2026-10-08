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
import org.springframework.transaction.annotation.Transactional;
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
import com.kw.knowone.task.entity.TaskModels.Occurrence;
import com.kw.knowone.task.repository.TaskRepository;

@Service
public class GroupService {
    private final GroupRepository repository;
    private final GroupEventRepository eventRepository;
    private final AuthRepository authRepository;
    private final AuthService authService;
    private final ScheduleMutationService scheduleMutations;
    private final RecipientLookupRateLimiter rateLimiter;
    private final Clock clock;
    private final TaskRepository tasks;

    public GroupService(GroupRepository repository, GroupEventRepository eventRepository,
            AuthRepository authRepository, AuthService authService, ScheduleMutationService scheduleMutations,
            RecipientLookupRateLimiter rateLimiter, Clock clock, TaskRepository tasks) {
        this.repository = repository;
        this.eventRepository = eventRepository;
        this.authRepository = authRepository;
        this.authService = authService;
        this.scheduleMutations = scheduleMutations;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.tasks = tasks;
    }

    public IdempotentResult leave(UUID groupId,UUID userId,GroupDtos.LeaveRequest request,String key,UUID requestId){
        return scheduleMutations.execute(userId,"G07:"+groupId,key,request,
                ()->validateGroupMutationAuthorization(groupId,userId),()->doLeave(groupId,userId,request,requestId));
    }

    private MutationResponse doLeave(UUID groupId,UUID userId,GroupDtos.LeaveRequest request,UUID requestId){
        GroupMember before=requireActiveMembership(groupId,userId);
        if(!"CAREGIVER".equals(before.role()))throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","CAREGIVER만 탈퇴할 수 있습니다.");
        if(before.version()!=request.expectedVersion())throw new ApiException(HttpStatus.CONFLICT,"VERSION_CONFLICT","멤버십이 변경되었습니다.",Map.of("currentVersion",before.version()));
        List<GroupMember> otherChildren=repository.findActiveMembers(groupId).stream()
                .filter(m->"CAREGIVER".equals(m.role())&&!m.id().equals(before.id())).toList();
        if(Integer.valueOf(1).equals(before.priority())&&!otherChildren.isEmpty()
                &&otherChildren.stream().noneMatch(m->Integer.valueOf(1).equals(m.priority())))
            throw new ApiException(HttpStatus.CONFLICT,"LAST_PRIMARY_CAREGIVER","마지막 주돌봄자녀는 탈퇴할 수 없습니다.");
        Instant now=clock.instant();List<UUID> released=new java.util.ArrayList<>();
        for(Occurrence occurrence:tasks.futureAssigned(groupId,userId,now)){
            if(tasks.releaseAssignee(occurrence.id(),occurrence.version())!=1)throw new IllegalStateException("Concurrent occurrence release");
            UUID handoff=tasks.openHandoff(groupId,occurrence.id(),"MEMBER_LEFT",userId,userId);
            tasks.cancelPendingNotifications(occurrence.id());tasks.cancelPendingDeliveries(occurrence.id());
            eventRepository.audit(groupId,userId,"TASK_RELEASED_MEMBER_LEFT","TASK_OCCURRENCE",occurrence.id(),
                    Map.of("assigneeUserId",userId,"version",occurrence.version()),Map.of("assigneeUserId","","version",occurrence.version()+1),requestId);
            if(handoff!=null)eventRepository.taskNotification(groupId,"HANDOFF_OPEN","handoff:"+handoff+":open",
                    occurrence.id(),handoff,null,occurrence.version()+1,Map.of("schemaVersion",1,"reason","MEMBER_LEFT"),now);
            eventRepository.syncOccurrenceNotifications(occurrence.id(),now);
            released.add(occurrence.id());
        }
        repository.cancelUndeliveredNotifications(groupId,userId);
        GroupMember after=repository.leave(before,now);
        eventRepository.audit(groupId,userId,"GROUP_MEMBER_LEFT","GROUP_MEMBER",after.id(),eventMember(before),eventMember(after),requestId);
        eventRepository.notification(groupId,"MEMBER_LEFT","member-left:"+after.id()+":"+after.version(),
                Map.of("schemaVersion",1,"memberId",after.id(),"releasedOccurrenceIds",released),now);
        return new MutationResponse(200,DataResponse.of(new GroupDtos.LeaveResponse("LEFT",released)));
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
        AppUser caller=authRepository.findActiveById(userId).orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED,"UNAUTHORIZED","인증 정보가 올바르지 않습니다."));
        if(!"CHILD".equals(caller.accountRole()))throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","자녀 계정만 부모를 조회할 수 있습니다.");
        if(repository.hasCurrentMembership(userId))throw new ApiException(HttpStatus.CONFLICT,"GROUP_ALREADY_CONNECTED","이미 연결된 공동체가 있습니다.");
        rateLimiter.consume(userId);
        String normalized=phoneNumber.replaceAll("[-\\s]","");
        if(!normalized.matches("^010[0-9]{8}$"))throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","전화번호 형식을 확인해 주세요.");
        CareGroup group = repository.findDemoRecipientByPhone(normalized)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                        "등록된 돌봄 대상을 찾을 수 없습니다."));
        boolean completed=group.parentProfileCompletedAt()!=null;
        String joinMode=repository.activeCaregiverCount(group.id())==0?"FIRST_JOIN":"APPROVAL_REQUIRED";
        return new RecipientLookup(group.recipientUserId(),group.id(),group.recipientDisplayName(),
                group.parentRelation(),completed,joinMode,group.version());
    }

    public IdempotentResult joinV11(UUID groupId,UUID userId,GroupDtos.JoinV11Request request,String key,UUID requestId){
        return scheduleMutations.execute(userId,"G04V11:"+groupId,key,request,
                ()->validateJoinV11(groupId,userId,request),()->doJoinV11(groupId,userId,request,requestId));
    }

    private void validateJoinV11(UUID groupId,UUID userId,GroupDtos.JoinV11Request request){
        AppUser user=authRepository.findActiveById(userId).orElseThrow(this::unauthorized);
        if(!"CHILD".equals(user.accountRole()))throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","자녀 계정만 연결할 수 있습니다.");
        CareGroup group=requireGroup(groupId);
        if(!group.recipientUserId().equals(request.recipientUserId()))throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","부모 정보가 일치하지 않습니다.");
    }

    private MutationResponse doJoinV11(UUID groupId,UUID userId,GroupDtos.JoinV11Request request,UUID requestId){
        GroupMember existing=repository.findMembership(groupId,userId).orElse(null);
        if(existing!=null && ("ACTIVE".equals(existing.status())||"PENDING".equals(existing.status()))){
            var row=repository.findLatestJoinForUser(userId).orElseThrow();
            return new MutationResponse(200,DataResponse.of(new GroupDtos.JoinResult(toJoin(row),toMember(existing),
                    "ACTIVE".equals(existing.status())?"READY":"WAITING_APPROVAL")));
        }
        if(repository.hasCurrentMembership(userId))throw new ApiException(HttpStatus.CONFLICT,"GROUP_ALREADY_CONNECTED","이미 연결된 공동체가 있습니다.");
        CareGroup group=requireGroup(groupId);Instant now=clock.instant();boolean first=repository.activeCaregiverCount(groupId)==0;
        if(first && group.parentProfileCompletedAt()==null){
            var profile=request.parentProfile();
            if(profile==null)throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"PARENT_PROFILE_REQUIRED","최초 부모 정보가 필요합니다.");
            if(!Set.of("MOTHER","FATHER").contains(profile.relation())||profile.birthYear()>java.time.Year.now(clock).getValue())
                throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","부모 정보를 확인해 주세요.");
            repository.completeParentProfile(group,userId,profile.relation(),profile.name().trim(),profile.birthYear(),now);
        }else if(group.parentProfileCompletedAt()!=null && request.parentProfile()!=null){
            throw new ApiException(HttpStatus.CONFLICT,"PARENT_PROFILE_IMMUTABLE","완료된 부모 정보는 변경할 수 없습니다.");
        }
        GroupMember member=first?repository.insertCaregiver(UUID.randomUUID(),groupId,userId,1)
                :repository.insertPendingCaregiver(UUID.randomUUID(),groupId,userId);
        var row=repository.insertJoinRequest(UUID.randomUUID(),groupId,userId,first?"APPROVED":"PENDING",
                first?"FIRST_JOIN":null,first?userId:null,now);
        eventRepository.audit(groupId,userId,first?"GROUP_MEMBER_JOINED":"GROUP_JOIN_REQUESTED","GROUP_MEMBER",member.id(),null,eventMember(member),requestId);
        return new MutationResponse(first?201:202,DataResponse.of(new GroupDtos.JoinResult(toJoin(row),toMember(member),first?"READY":"WAITING_APPROVAL")));
    }

    public GroupDtos.CurrentJoin currentJoin(UUID userId){
        var row=repository.findLatestJoinForUser(userId).orElse(null);
        return row==null?new GroupDtos.CurrentJoin(null,null):new GroupDtos.CurrentJoin(toJoin(row),
                repository.findMembership(row.groupId(),userId).map(this::toMember).orElse(null));
    }

    public GroupDtos.Items<GroupDtos.PendingJoin> pendingJoins(UUID groupId,UUID actor){
        requirePrimary(groupId,actor);
        return new GroupDtos.Items<>(repository.findPendingJoins(groupId).stream().map(r->{
            GroupMember m=repository.findMembership(groupId,r.userId()).orElseThrow();
            return new GroupDtos.PendingJoin(r.id(),new UserRef(r.userId(),m.displayName()),r.createdAt(),r.version());
        }).toList());
    }

    @Transactional
    public GroupDtos.JoinRequestView decideJoin(UUID groupId,UUID requestId,UUID actor,GroupDtos.JoinDecisionRequest body){
        requirePrimary(groupId,actor);
        if(!Set.of("APPROVE","REJECT").contains(body.decision()))throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","결정을 확인해 주세요.");
        var row=repository.findJoinRequest(requestId).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","신청을 찾을 수 없습니다."));
        if(!row.groupId().equals(groupId))throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","다른 공동체의 신청입니다.");
        if(!"PENDING".equals(row.status()))throw new ApiException(HttpStatus.CONFLICT,"REQUEST_ALREADY_DECIDED","이미 처리된 신청입니다.");
        if(row.version()!=body.expectedVersion())throw new ApiException(HttpStatus.CONFLICT,"VERSION_CONFLICT","신청이 변경되었습니다.");
        var changed=repository.decideJoin(row,actor,body.decision(),clock.instant());
        if(changed==null)throw new ApiException(HttpStatus.CONFLICT,"REQUEST_ALREADY_DECIDED","이미 처리된 신청입니다.");
        return toJoin(changed);
    }

    @Transactional
    public GroupDtos.JoinRequestView cancelJoin(UUID requestId,UUID actor,GroupDtos.CancelJoinRequest body){
        var row=repository.findJoinRequest(requestId).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","신청을 찾을 수 없습니다."));
        if(!row.userId().equals(actor))throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","본인의 신청만 취소할 수 있습니다.");
        if(!"PENDING".equals(row.status()))throw new ApiException(HttpStatus.CONFLICT,"REQUEST_ALREADY_DECIDED","이미 처리된 신청입니다.");
        if(row.version()!=body.expectedVersion())throw new ApiException(HttpStatus.CONFLICT,"VERSION_CONFLICT","신청이 변경되었습니다.");
        var changed=repository.cancelJoin(row,clock.instant());
        if(changed==null)throw new ApiException(HttpStatus.CONFLICT,"REQUEST_ALREADY_DECIDED","이미 처리된 신청입니다.");
        return toJoin(changed);
    }

    private void requirePrimary(UUID groupId,UUID actor){
        GroupMember member=requireActiveMembership(groupId,actor);
        if(!"CAREGIVER".equals(member.role())||!Integer.valueOf(1).equals(member.priority()))
            throw new ApiException(HttpStatus.FORBIDDEN,"PRIMARY_CAREGIVER_REQUIRED","주돌봄자녀 권한이 필요합니다.");
    }

    private GroupDtos.JoinRequestView toJoin(GroupRepository.JoinRow r){return new GroupDtos.JoinRequestView(r.id(),r.groupId(),r.status(),r.version(),r.createdAt(),r.decidedAt(),r.membershipStatus(),r.priority());}

    private ApiException unauthorized(){return new ApiException(HttpStatus.UNAUTHORIZED,"UNAUTHORIZED","인증 정보가 올바르지 않습니다.");}

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
        Map<UUID,Integer> requestedPriorities=request.members().stream().collect(java.util.stream.Collectors.toMap(PriorityItem::memberId,PriorityItem::priority));
        List<GroupMember> caregivers=repository.findActiveMembers(groupId).stream().filter(m->"CAREGIVER".equals(m.role())).toList();
        if(!caregivers.isEmpty()&&caregivers.stream().noneMatch(m->requestedPriorities.getOrDefault(m.id(),m.priority())==1))
            throw new ApiException(HttpStatus.CONFLICT,"LAST_PRIMARY_CAREGIVER","주돌봄자녀가 최소 한 명 필요합니다.");
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
        GroupDtos.ParentProfile profile=group.parentProfileCompletedAt()==null?null:new GroupDtos.ParentProfile(
                group.parentRelation(),group.recipientDisplayName(),group.parentBirthYear(),group.parentProfileCompletedAt());
        return new Group(group.id(), group.name(),
                new UserRef(group.recipientUserId(), group.recipientDisplayName()),profile, group.status(),
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
