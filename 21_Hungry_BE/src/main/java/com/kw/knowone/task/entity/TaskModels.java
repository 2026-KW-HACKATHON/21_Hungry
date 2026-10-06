package com.kw.knowone.task.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class TaskModels {
    private TaskModels() { }
    public record Occurrence(UUID id, UUID groupId, UUID seriesId, long seriesVersion, int revisionNo,
            LocalDate anchorDate, String kind, String title, String description, Instant startsAt, Instant endsAt,
            String status, UUID assigneeUserId, String assigneeName, String assignmentOrigin, boolean override,
            UUID completedBy, String completedByName, UUID performedBy, String performedByName, Instant completedAt,
            String cancelReason, Instant canceledAt, long version) { }
    public record Series(UUID id, UUID groupId, String kind, String title, String description, String recurrence,
            LocalDate firstDate, LocalDate lastDate, List<Integer> weekdays, LocalTime localTime,
            int durationMinutes, LocalDate stopFromDate, int currentRevisionNo, long version) { }
    public record Candidate(UUID memberId, UUID userId, int priority) { }
    public record Handoff(UUID id, UUID occurrenceId, String reason, UUID previousId, String previousName,
            UUID requestedBy, String requestedName, String status, UUID acceptedBy, String acceptedName,
            Instant closedAt, String closeReason, long version) { }
    public record Audit(UUID id, String eventType, UUID actorId, String actorName, String beforeJson,
            String afterJson, Instant createdAt) { }
}
