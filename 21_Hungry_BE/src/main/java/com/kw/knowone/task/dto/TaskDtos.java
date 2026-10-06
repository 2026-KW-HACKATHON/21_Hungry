package com.kw.knowone.task.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class TaskDtos {
    private TaskDtos() { }
    public record UserRef(UUID id, String displayName) { }
    public record Handoff(UUID id, UUID occurrenceId, String reason, UserRef previousAssignee,
            UserRef requestedBy, String status, UserRef acceptedBy, OffsetDateTime closedAt,
            String closeReason, long version) { }
    public record Completion(UserRef performedBy, UserRef completedBy, OffsetDateTime completedAt) { }
    public record Cancellation(String reason, OffsetDateTime canceledAt) { }
    public record Medication(UUID id, String name, String doseText, String frequencyText, LocalDate startsOn,
            LocalDate endsOn, String instructions, UserRef confirmedBy, OffsetDateTime confirmedAt,
            UUID supersedesId) { }
    public record Task(UUID id, UUID groupId, UUID seriesId, long seriesVersion, int revisionNo,
            LocalDate anchorDate, String kind, String title, String description, OffsetDateTime startsAt,
            OffsetDateTime endsAt, OffsetDateTime dueAt, String executionStatus, UserRef assignee,
            String assignmentOrigin, Handoff openHandoff, boolean isOverdue, boolean isOverride,
            List<Medication> medications, Completion completion, Cancellation cancellation,
            List<UUID> sourceEncounterIds, long version) { }
    public record Page<T>(List<T> items, String nextCursor, boolean hasMore) { }
    public record Rule(@NotNull String recurrence, @NotNull LocalDate firstDate, @NotNull LocalDate lastDate,
            @NotNull List<@Min(1) @Max(7) Integer> weekdays, @NotNull LocalTime localTime,
            @Min(1) @Max(1440) int durationMinutes) { }
    public record CreateRequest(@NotBlank @Size(max=16) String kind, @NotBlank @Size(max=150) String title,
            String description, @NotNull @Valid Rule rule, List<UUID> medicationIds) { }
    public record GenerationWindow(LocalDate fromDate, LocalDate toDateExclusive) { }
    public record CreateResponse(UUID seriesId, long seriesVersion, Rule rule, List<Task> occurrences,
            GenerationWindow generationWindow) { }
    public record Series(UUID id, UUID groupId, String kind, String title, String description, Rule rule,
            List<Medication> medications, LocalDate stopFromDate, int currentRevisionNo, long version) { }
    public record AssignmentRequest(@Min(0) long expectedVersion, @NotNull UUID assigneeUserId) { }
    public record CompleteRequest(@Min(0) long expectedVersion, @NotNull UUID performedByUserId) { }
    public record ReopenRequest(@Min(0) long expectedVersion) { }
    public record OccurrenceResponse(Task occurrence) { }
    public record Change(String field, Object before, Object after) { }
    public record History(UUID id, String eventType, UserRef actor, OffsetDateTime createdAt, List<Change> changes) { }
}
