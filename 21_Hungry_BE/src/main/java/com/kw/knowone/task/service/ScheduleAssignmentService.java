package com.kw.knowone.task.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import com.kw.knowone.availability.repository.AvailabilityRepository;
import com.kw.knowone.availability.service.AvailabilityService;
import com.kw.knowone.task.entity.TaskModels.Candidate;
import com.kw.knowone.task.repository.TaskRepository;

@Service
public class ScheduleAssignmentService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final TaskRepository tasks;
    private final AvailabilityRepository availability;
    private final AvailabilityService availabilityService;

    public ScheduleAssignmentService(TaskRepository tasks, AvailabilityRepository availability,
            AvailabilityService availabilityService) {
        this.tasks = tasks; this.availability = availability; this.availabilityService = availabilityService;
    }

    public UUID select(UUID groupId, Instant startsAt, Instant endsAt) {
        ZonedDateTime local = startsAt.atZone(KST);
        LocalDate monday = local.toLocalDate().with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        Instant weekStart = monday.atStartOfDay(KST).toInstant();
        Instant weekEnd = monday.plusDays(7).atStartOfDay(KST).toInstant();
        return eligible(groupId, startsAt, endsAt).stream()
                .map(candidate -> new Scored(candidate,
                        tasks.weeklyCount(groupId, candidate.userId(), weekStart, weekEnd)))
                .min(Comparator.comparingInt((Scored value) -> value.candidate().priority())
                        .thenComparingInt(Scored::weeklyCount)
                        .thenComparing(value -> value.candidate().memberId().toString()))
                .map(value -> value.candidate().userId()).orElse(null);
    }

    public List<Candidate> eligible(UUID groupId, Instant startsAt, Instant endsAt) {
        return tasks.candidates(groupId).stream()
                .filter(value -> availabilityService.isAvailable(value.userId(), startsAt, endsAt))
                .filter(value -> !availability.hasConflict(value.userId(), startsAt, endsAt, null)).toList();
    }

    public boolean canAssign(UUID userId, Instant startsAt, Instant endsAt, UUID excludedOccurrence) {
        return isAvailable(userId, startsAt, endsAt)
                && !hasConflict(userId, startsAt, endsAt, excludedOccurrence);
    }

    public boolean isAvailable(UUID userId, Instant startsAt, Instant endsAt) {
        return availabilityService.isAvailable(userId, startsAt, endsAt);
    }

    public boolean hasConflict(UUID userId, Instant startsAt, Instant endsAt, UUID excludedOccurrence) {
        return availability.hasConflict(userId, startsAt, endsAt, excludedOccurrence);
    }

    private record Scored(Candidate candidate, int weeklyCount) { }
}
