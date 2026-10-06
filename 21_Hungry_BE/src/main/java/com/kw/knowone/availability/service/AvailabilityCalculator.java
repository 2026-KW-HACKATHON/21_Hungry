package com.kw.knowone.availability.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import com.kw.knowone.availability.entity.AvailabilityModels.Day;
import com.kw.knowone.availability.entity.AvailabilityModels.MinuteInterval;
import com.kw.knowone.availability.entity.AvailabilityModels.WorkPeriod;
import com.kw.knowone.common.web.ApiException;

@Component
public class AvailabilityCalculator {

    public List<MinuteInterval> normalizeIntervals(List<MinuteInterval> source, int activeStart, int activeEnd,
            boolean clip) {
        List<MinuteInterval> sorted = new ArrayList<>();
        for (MinuteInterval interval : source == null ? List.<MinuteInterval>of() : source) {
            if (interval.startMinute() < 0 || interval.endMinute() > 1440
                    || interval.startMinute() >= interval.endMinute()) {
                throw validation("가능 시간 구간이 올바르지 않습니다.");
            }
            int start = clip ? Math.max(activeStart, interval.startMinute()) : interval.startMinute();
            int end = clip ? Math.min(activeEnd, interval.endMinute()) : interval.endMinute();
            if (!clip && (start < activeStart || end > activeEnd)) {
                throw validation("직접 가능 구간은 활동시간 안에 있어야 합니다.");
            }
            if (start < end) sorted.add(new MinuteInterval(start, end));
        }
        sorted.sort(Comparator.comparingInt(MinuteInterval::startMinute).thenComparingInt(MinuteInterval::endMinute));
        List<MinuteInterval> merged = new ArrayList<>();
        for (MinuteInterval next : sorted) {
            if (merged.isEmpty() || next.startMinute() > merged.getLast().endMinute()) {
                merged.add(next);
            } else {
                MinuteInterval previous = merged.removeLast();
                merged.add(new MinuteInterval(previous.startMinute(), Math.max(previous.endMinute(), next.endMinute())));
            }
        }
        return List.copyOf(merged);
    }

    public List<WorkPeriod> normalizeWork(List<WorkPeriod> source) {
        List<WorkPeriod> split = new ArrayList<>();
        for (WorkPeriod period : source == null ? List.<WorkPeriod>of() : source) {
            if (period.isoWeekday() < 1 || period.isoWeekday() > 7 || period.startMinute() < 0
                    || period.startMinute() > 1439 || period.endMinute() < 0 || period.endMinute() > 1440
                    || period.startMinute() == period.endMinute()) {
                throw validation("요일 근무 구간이 올바르지 않습니다.");
            }
            if (period.startMinute() < period.endMinute()) {
                split.add(period);
            } else {
                split.add(new WorkPeriod(period.isoWeekday(), period.startMinute(), 1440));
                if (period.endMinute() > 0) {
                    split.add(new WorkPeriod(period.isoWeekday() == 7 ? 1 : period.isoWeekday() + 1, 0,
                            period.endMinute()));
                }
            }
        }
        List<WorkPeriod> result = new ArrayList<>();
        for (int weekday = 1; weekday <= 7; weekday++) {
            int day = weekday;
            List<MinuteInterval> merged = normalizeIntervals(split.stream()
                    .filter(value -> value.isoWeekday() == day)
                    .map(value -> new MinuteInterval(value.startMinute(), value.endMinute())).toList(), 0, 1440, false);
            for (MinuteInterval interval : merged) result.add(new WorkPeriod(day, interval.startMinute(), interval.endMinute()));
        }
        return List.copyOf(result);
    }

    public List<MinuteInterval> calculate(String mode, boolean custom, List<MinuteInterval> direct,
            LocalDate date, int activeStart, int activeEnd, List<WorkPeriod> workPeriods, boolean clipCustom) {
        if ("UNAVAILABLE".equals(mode)) return List.of();
        if ("FULL".equals(mode)) return List.of(new MinuteInterval(activeStart, activeEnd));
        if (!"PARTIAL".equals(mode)) throw validation("가능 시간 mode가 올바르지 않습니다.");
        if (custom) return normalizeIntervals(direct, activeStart, activeEnd, clipCustom);
        List<MinuteInterval> available = new ArrayList<>();
        available.add(new MinuteInterval(activeStart, activeEnd));
        int weekday = date.getDayOfWeek().getValue();
        for (WorkPeriod work : workPeriods) {
            if (work.isoWeekday() != weekday) continue;
            available = subtract(available, new MinuteInterval(work.startMinute(), work.endMinute()));
        }
        return normalizeIntervals(available, 0, 1440, false);
    }

    public boolean covers(DayProvider provider, Instant startsAt, Instant endsAt, java.time.ZoneId zone) {
        ZonedDateTime cursor = startsAt.atZone(zone);
        ZonedDateTime end = endsAt.atZone(zone);
        while (cursor.toLocalDate().isBefore(end.toLocalDate()) || cursor.isBefore(end)) {
            LocalDate date = cursor.toLocalDate();
            ZonedDateTime nextMidnight = date.plusDays(1).atStartOfDay(zone);
            ZonedDateTime segmentEnd = end.isBefore(nextMidnight) ? end : nextMidnight;
            int startMinute = cursor.getHour() * 60 + cursor.getMinute();
            int endMinute = segmentEnd.equals(nextMidnight) ? 1440
                    : segmentEnd.getHour() * 60 + segmentEnd.getMinute();
            if (segmentEnd.getSecond() != 0 || segmentEnd.getNano() != 0) endMinute++;
            final int requiredEndMinute = endMinute;
            List<MinuteInterval> intervals = provider.intervals(date);
            boolean included = intervals.stream().anyMatch(value -> value.startMinute() <= startMinute
                    && value.endMinute() >= requiredEndMinute);
            if (!included) return false;
            if (!segmentEnd.isBefore(end)) break;
            cursor = nextMidnight;
        }
        return true;
    }

    private ArrayList<MinuteInterval> subtract(List<MinuteInterval> source, MinuteInterval blocked) {
        ArrayList<MinuteInterval> result = new ArrayList<>();
        for (MinuteInterval current : source) {
            if (blocked.endMinute() <= current.startMinute() || blocked.startMinute() >= current.endMinute()) {
                result.add(current);
                continue;
            }
            if (blocked.startMinute() > current.startMinute())
                result.add(new MinuteInterval(current.startMinute(), Math.min(blocked.startMinute(), current.endMinute())));
            if (blocked.endMinute() < current.endMinute())
                result.add(new MinuteInterval(Math.max(blocked.endMinute(), current.startMinute()), current.endMinute()));
        }
        return result;
    }

    private ApiException validation(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    @FunctionalInterface
    public interface DayProvider {
        List<MinuteInterval> intervals(LocalDate date);
    }
}
