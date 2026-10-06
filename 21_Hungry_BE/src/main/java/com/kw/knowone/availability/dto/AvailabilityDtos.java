package com.kw.knowone.availability.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public final class AvailabilityDtos {
    private AvailabilityDtos() { }

    public record Interval(@Min(0) @Max(1439) int startMinute, @Min(1) @Max(1440) int endMinute) { }
    public record WorkPeriod(@Min(1) @Max(7) int isoWeekday, @Min(0) @Max(1439) int startMinute,
            @Min(0) @Max(1440) int endMinute) { }
    public record Config(int activeStartMinute, int activeEndMinute, long userVersion,
            String workConfigVersion, List<WorkPeriod> weeklyWorkPeriods) { }
    public record ConfigPatch(Integer activeStartMinute, Integer activeEndMinute,
            List<@Valid WorkPeriod> weeklyWorkPeriods) { }
    public record ConfigPreviewRequest(@PositiveOrZero long expectedUserVersion,
            @NotNull String expectedWorkConfigVersion, @NotNull @Valid ConfigPatch patch) { }
    public record ConfigSaveRequest(@PositiveOrZero long expectedUserVersion,
            @NotNull String expectedWorkConfigVersion, @NotNull String previewToken,
            @NotNull @Valid ConfigPatch patch) { }
    public record Preview(String previewToken, OffsetDateTime expiresAt, int recalculatedDateCount,
            List<LocalDate> clippedCustomDates, List<UUID> releasedOccurrenceIds,
            int retainedPastOccurrenceCount) { }
    public record ConfigSave(int activeStartMinute, int activeEndMinute, long userVersion,
            String workConfigVersion, List<WorkPeriod> weeklyWorkPeriods, List<UUID> releasedOccurrenceIds) { }
    public record Day(LocalDate date, String mode, boolean customIntervals, List<Interval> intervals, Long version) { }
    public record Days(List<Day> items) { }
    public record ExpectedDay(@NotNull LocalDate date, Long version) { }
    public record DaysRequest(@NotNull LocalDate fromDate, @NotNull LocalDate toDateExclusive,
            @NotNull String mode, boolean customIntervals, List<@Valid Interval> intervals,
            @NotNull List<@Valid ExpectedDay> expectedDays) { }
    public record DaysSaveRequest(@NotNull LocalDate fromDate, @NotNull LocalDate toDateExclusive,
            @NotNull String mode, boolean customIntervals, List<@Valid Interval> intervals,
            @NotNull List<@Valid ExpectedDay> expectedDays, @NotNull String previewToken) { }
    public record DaysPreview(String previewToken, OffsetDateTime expiresAt, int recalculatedDateCount,
            List<LocalDate> clippedCustomDates, List<UUID> releasedOccurrenceIds,
            int retainedPastOccurrenceCount, List<Day> days) { }
    public record DaysSave(List<Day> items, List<UUID> releasedOccurrenceIds) { }
}
