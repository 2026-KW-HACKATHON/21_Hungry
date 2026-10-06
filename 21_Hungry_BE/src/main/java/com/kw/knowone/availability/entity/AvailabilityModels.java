package com.kw.knowone.availability.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class AvailabilityModels {
    private AvailabilityModels() { }

    public record MinuteInterval(int startMinute, int endMinute) { }
    public record WorkPeriod(int isoWeekday, int startMinute, int endMinute) { }
    public record UserConfig(UUID userId, int activeStartMinute, int activeEndMinute, long version) { }
    public record Day(UUID id, UUID userId, LocalDate date, String mode, boolean customIntervals,
            long version, List<MinuteInterval> intervals) { }
    public record AssignedOccurrence(UUID id, UUID groupId, Instant startsAt, Instant endsAt, long version,
            boolean groupAccessible) { }
}
