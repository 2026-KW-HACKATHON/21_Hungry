package com.kw.knowone.group.dto;

import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.Instant;
import com.kw.knowone.task.dto.TaskDtos;

public final class GroupDtos {
    private GroupDtos() { }

    public record UserRef(UUID id, String displayName) { }
    public record Member(UUID id, UserRef user, String role, Integer priority, String status, long version) { }
    public record ParentProfile(String relation, String name, Integer birthYear, Instant completedAt) { }
    public record Group(UUID id, String name, UserRef recipient, ParentProfile parentProfile,
            String status, Member myMembership, long version) { }
    public record Items<T>(List<T> items) { }
    public record RecipientLookupRequest(@NotBlank @Size(max = 20) String phoneNumber) { }
    public record RecipientLookup(UUID recipientUserId, UUID groupId, String displayName, String parentRelation,
            boolean parentProfileCompleted, String joinMode, long groupVersion) { }
    public record JoinRequest(@NotNull UUID recipientUserId, @NotBlank @Size(max = 50) String confirmedName) { }
    public record PriorityRequest(@NotEmpty List<@Valid PriorityItem> items) { }
    public record PriorityItem(@NotNull UUID memberId, @Min(1) @Max(2) int priority,
            @PositiveOrZero long expectedVersion) { }
    public record LeaveRequest(@PositiveOrZero long expectedVersion) { }
    public record LeaveResponse(Member membership, List<UUID> releasedOccurrenceIds) { }
    public record HomeTaskList(List<TaskDtos.Task> items, boolean hasMore) { }
    public record Home(UUID groupId, LocalDate date, HomeTaskList todayMyTasks,
            HomeTaskList unassignedFutureTasks, HomeTaskList overdueTasks, int reviewEncounterCount) { }
    public record ParentProfileRequest(@NotBlank String relation, @NotBlank @Size(max=50) String name,
            @Min(1900) int birthYear) { }
    public record JoinV11Request(@NotNull UUID recipientUserId, @Valid ParentProfileRequest parentProfile) { }
    public record JoinRequestView(UUID id, UUID groupId, String status, long version, Instant createdAt,
            Instant decidedAt, String membershipStatus, Integer priority) { }
    public record JoinResult(JoinRequestView request, Member membership, String nextAction) { }
    public record CurrentJoin(JoinRequestView request, Member membership) { }
    public record PendingJoin(UUID id, UserRef user, Instant createdAt, long version) { }
    public record JoinRequestPage(List<PendingJoin> items,String nextCursor,boolean hasMore) { }
    public record JoinDecisionRequest(@PositiveOrZero long expectedVersion, @NotBlank String decision) { }
    public record CancelJoinRequest(@PositiveOrZero long expectedVersion) { }
    public record JoinMutationResponse(JoinRequestView request) { }
}
