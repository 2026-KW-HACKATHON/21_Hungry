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
import com.kw.knowone.task.dto.TaskDtos;

public final class GroupDtos {
    private GroupDtos() { }

    public record UserRef(UUID id, String displayName) { }
    public record Member(UUID id, UserRef user, String role, Integer priority, String status, long version) { }
    public record Group(UUID id, String name, UserRef recipient, String status, Member myMembership, long version) { }
    public record Items<T>(List<T> items) { }
    public record RecipientLookupRequest(@NotBlank @Size(max = 20) String phoneNumber) { }
    public record RecipientLookup(UUID recipientUserId, String displayName, UUID groupId) { }
    public record JoinRequest(@NotNull UUID recipientUserId, @NotBlank @Size(max = 50) String confirmedName) { }
    public record PriorityRequest(@NotEmpty List<@Valid PriorityItem> members) { }
    public record PriorityItem(@NotNull UUID memberId, @Min(1) @Max(99) int priority,
            @PositiveOrZero long expectedVersion) { }
    public record LeaveRequest(@PositiveOrZero long expectedVersion) { }
    public record LeaveResponse(String membershipStatus, List<UUID> releasedOccurrenceIds) { }
    public record HomeTaskList(List<TaskDtos.Task> items, boolean hasMore) { }
    public record Home(UUID groupId, LocalDate date, HomeTaskList todayMyTasks,
            HomeTaskList unassignedFutureTasks, HomeTaskList overdueTasks, int reviewEncounterCount) { }
}
