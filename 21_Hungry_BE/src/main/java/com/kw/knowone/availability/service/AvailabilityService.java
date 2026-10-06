package com.kw.knowone.availability.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.kw.knowone.availability.dto.AvailabilityDtos;
import com.kw.knowone.availability.dto.AvailabilityDtos.ConfigPatch;
import com.kw.knowone.availability.dto.AvailabilityDtos.ExpectedDay;
import com.kw.knowone.availability.entity.AvailabilityModels.AssignedOccurrence;
import com.kw.knowone.availability.entity.AvailabilityModels.Day;
import com.kw.knowone.availability.entity.AvailabilityModels.MinuteInterval;
import com.kw.knowone.availability.entity.AvailabilityModels.UserConfig;
import com.kw.knowone.availability.entity.AvailabilityModels.WorkPeriod;
import com.kw.knowone.availability.repository.AvailabilityRepository;
import com.kw.knowone.common.crypto.FingerprintService;
import com.kw.knowone.common.idempotency.IdempotentResult;
import com.kw.knowone.common.idempotency.MutationResponse;
import com.kw.knowone.common.preview.PreviewTokenService;
import com.kw.knowone.common.preview.PreviewTokenService.Claims;
import com.kw.knowone.common.schedule.ScheduleMutationService;
import com.kw.knowone.common.web.ApiException;
import com.kw.knowone.common.web.DataResponse;
import com.kw.knowone.group.repository.GroupEventRepository;

@Service
public class AvailabilityService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate FAR_FUTURE = LocalDate.of(9999, 12, 31);
    private final AvailabilityRepository repository;
    private final AvailabilityCalculator calculator;
    private final FingerprintService fingerprints;
    private final PreviewTokenService previewTokens;
    private final ScheduleMutationService mutations;
    private final GroupEventRepository events;
    private final Clock clock;

    public AvailabilityService(AvailabilityRepository repository, AvailabilityCalculator calculator,
            FingerprintService fingerprints, PreviewTokenService previewTokens, ScheduleMutationService mutations,
            GroupEventRepository events, Clock clock) {
        this.repository = repository;
        this.calculator = calculator;
        this.fingerprints = fingerprints;
        this.previewTokens = previewTokens;
        this.mutations = mutations;
        this.events = events;
        this.clock = clock;
    }

    public AvailabilityDtos.Config config(UUID userId) {
        UserConfig config = requireConfig(userId, false);
        List<WorkPeriod> work = repository.findWorkPeriods(userId);
        return configDto(config, work);
    }

    public AvailabilityDtos.Preview previewConfig(UUID userId, AvailabilityDtos.ConfigPreviewRequest request) {
        UserConfig current = requireConfig(userId, false);
        List<WorkPeriod> currentWork = repository.findWorkPeriods(userId);
        validateExpectedConfig(current, currentWork, request.expectedUserVersion(), request.expectedWorkConfigVersion());
        ProposedConfig proposed = proposedConfig(current, currentWork, request.patch());
        Impact impact = configImpact(userId, proposed);
        String payload = fingerprints.of(configCore(request.expectedUserVersion(),
                request.expectedWorkConfigVersion(), request.patch()));
        String state = fingerprints.of(impact.state(current.version(), workFingerprint(currentWork)));
        String token = previewTokens.issue(userId, "V03", payload, state);
        Claims claims = previewTokens.verify(token, userId, "V03", payload);
        return new AvailabilityDtos.Preview(token, atKst(claims.expiresAt()), impact.recalculatedCount(),
                impact.clippedDates(), impact.visibleReleasedIds(), impact.retainedPastCount());
    }

    public IdempotentResult saveConfig(UUID userId, AvailabilityDtos.ConfigSaveRequest request, String key,
            UUID requestId) {
        Object core = configCore(request.expectedUserVersion(), request.expectedWorkConfigVersion(), request.patch());
        return mutations.execute(userId, "V03", key, request, () -> requireConfig(userId, false), () -> {
            UserConfig current = requireConfig(userId, true);
            List<WorkPeriod> currentWork = repository.findWorkPeriods(userId);
            validateExpectedConfig(current, currentWork, request.expectedUserVersion(), request.expectedWorkConfigVersion());
            ProposedConfig proposed = proposedConfig(current, currentWork, request.patch());
            Impact impact = configImpact(userId, proposed);
            Claims claims = previewTokens.verify(request.previewToken(), userId, "V03", fingerprints.of(core));
            requireSameState(claims, impact.state(current.version(), workFingerprint(currentWork)));

            if (repository.updateConfig(userId, current.version(), proposed.start(), proposed.end()) != 1)
                throw versionConflict("사용자 설정이 변경되었습니다.");
            repository.replaceWorkPeriods(userId, proposed.work());
            for (DayChange change : impact.dayChanges()) {
                repository.updateDay(change.before(), change.before().mode(), change.before().customIntervals(),
                        change.intervals());
            }
            releaseOccurrences(userId, impact.released(), requestId);
            UserConfig saved = requireConfig(userId, false);
            List<WorkPeriod> savedWork = repository.findWorkPeriods(userId);
            AvailabilityDtos.ConfigSave body = new AvailabilityDtos.ConfigSave(saved.activeStartMinute(),
                    saved.activeEndMinute(), saved.version(), workFingerprint(savedWork), workDtos(savedWork),
                    impact.visibleReleasedIds());
            return new MutationResponse(200, DataResponse.of(body));
        });
    }

    public AvailabilityDtos.Days days(UUID userId, LocalDate from, LocalDate toExclusive) {
        validateRange(from, toExclusive);
        Map<LocalDate, Day> existing = repository.findDays(userId, from, toExclusive);
        List<AvailabilityDtos.Day> result = new ArrayList<>();
        for (LocalDate date = from; date.isBefore(toExclusive); date = date.plusDays(1))
            result.add(dayDto(date, existing.get(date)));
        return new AvailabilityDtos.Days(result);
    }

    public AvailabilityDtos.DaysPreview previewDays(UUID userId, AvailabilityDtos.DaysRequest request) {
        DayPlan plan = dayPlan(userId, request.fromDate(), request.toDateExclusive(), request.mode(),
                request.customIntervals(), request.intervals(), request.expectedDays());
        String payload = fingerprints.of(daysCore(request.fromDate(), request.toDateExclusive(), request.mode(),
                request.customIntervals(), request.intervals(), request.expectedDays()));
        String state = fingerprints.of(plan.impact().state(null, null));
        String token = previewTokens.issue(userId, "V06", payload, state);
        Claims claims = previewTokens.verify(token, userId, "V06", payload);
        return new AvailabilityDtos.DaysPreview(token, atKst(claims.expiresAt()), plan.days().size(), List.of(),
                plan.impact().visibleReleasedIds(), plan.impact().retainedPastCount(), plan.previewDays());
    }

    public IdempotentResult saveDays(UUID userId, AvailabilityDtos.DaysSaveRequest request, String key,
            UUID requestId) {
        Object core = daysCore(request.fromDate(), request.toDateExclusive(), request.mode(),
                request.customIntervals(), request.intervals(), request.expectedDays());
        return mutations.execute(userId, "V06", key, request, () -> requireConfig(userId, false), () -> {
            DayPlan plan = dayPlan(userId, request.fromDate(), request.toDateExclusive(), request.mode(),
                    request.customIntervals(), request.intervals(), request.expectedDays());
            Claims claims = previewTokens.verify(request.previewToken(), userId, "V06", fingerprints.of(core));
            requireSameState(claims, plan.impact().state(null, null));
            List<AvailabilityDtos.Day> saved = new ArrayList<>();
            for (PlannedDay planned : plan.days()) {
                Day row = planned.before() == null
                        ? repository.insertDay(userId, planned.date(), planned.mode(), planned.custom(), planned.intervals())
                        : repository.updateDay(planned.before(), planned.mode(), planned.custom(), planned.intervals());
                saved.add(dayDto(row.date(), row));
            }
            releaseOccurrences(userId, plan.impact().released(), requestId);
            return new MutationResponse(200, DataResponse.of(new AvailabilityDtos.DaysSave(saved,
                    plan.impact().visibleReleasedIds())));
        });
    }

    public boolean isAvailable(UUID userId, Instant startsAt, Instant endsAt) {
        return calculator.covers(date -> repository.findDay(userId, date).map(Day::intervals).orElse(List.of()),
                startsAt, endsAt, KST);
    }

    private ProposedConfig proposedConfig(UserConfig current, List<WorkPeriod> currentWork, ConfigPatch patch) {
        if (patch.activeStartMinute() == null && patch.activeEndMinute() == null && patch.weeklyWorkPeriods() == null)
            throw validation("patch에는 최소 한 필드가 필요합니다.");
        int start = patch.activeStartMinute() == null ? current.activeStartMinute() : patch.activeStartMinute();
        int end = patch.activeEndMinute() == null ? current.activeEndMinute() : patch.activeEndMinute();
        if (start < 0 || start > 1439 || end < 1 || end > 1440 || start >= end)
            throw validation("활동 시간 범위가 올바르지 않습니다.");
        List<WorkPeriod> work = patch.weeklyWorkPeriods() == null ? currentWork
                : calculator.normalizeWork(patch.weeklyWorkPeriods().stream().map(this::entity).toList());
        return new ProposedConfig(start, end, work);
    }

    private Impact configImpact(UUID userId, ProposedConfig proposed) {
        LocalDate today = LocalDate.now(clock);
        Map<LocalDate, Day> futureDays = repository.findDays(userId, today, FAR_FUTURE);
        Map<LocalDate, List<MinuteInterval>> overrides = new HashMap<>();
        List<DayChange> changes = new ArrayList<>();
        List<LocalDate> clipped = new ArrayList<>();
        int recalculated = 0;
        for (Day day : futureDays.values()) {
            List<MinuteInterval> next = calculator.calculate(day.mode(), day.customIntervals(), day.intervals(),
                    day.date(), proposed.start(), proposed.end(), proposed.work(), true);
            overrides.put(day.date(), next);
            if (day.customIntervals() && !next.equals(day.intervals())) clipped.add(day.date());
            if (!day.customIntervals()) recalculated++;
            if (!next.equals(day.intervals())) changes.add(new DayChange(day, next));
        }
        Impact base = impact(userId, overrides, recalculated, clipped);
        return new Impact(base.recalculatedCount(), base.clippedDates(), base.released(),
                base.visibleReleasedIds(), base.retainedPastCount(), List.copyOf(changes));
    }

    private DayPlan dayPlan(UUID userId, LocalDate from, LocalDate to, String mode, boolean custom,
            List<AvailabilityDtos.Interval> requestIntervals, List<ExpectedDay> expectedDays) {
        validateRange(from, to);
        validateMode(mode, custom, requestIntervals);
        UserConfig config = requireConfig(userId, false);
        List<WorkPeriod> work = repository.findWorkPeriods(userId);
        Map<LocalDate, Day> current = repository.findDays(userId, from, to);
        validateExpectedDays(from, to, expectedDays, current);
        List<MinuteInterval> direct = requestIntervals == null ? List.of()
                : requestIntervals.stream().map(this::entity).toList();
        List<PlannedDay> days = new ArrayList<>();
        Map<LocalDate, List<MinuteInterval>> overrides = new HashMap<>();
        List<AvailabilityDtos.Day> preview = new ArrayList<>();
        for (LocalDate date = from; date.isBefore(to); date = date.plusDays(1)) {
            List<MinuteInterval> intervals = calculator.calculate(mode, custom, direct, date,
                    config.activeStartMinute(), config.activeEndMinute(), work, false);
            Day before = current.get(date);
            days.add(new PlannedDay(date, mode, custom, intervals, before));
            overrides.put(date, intervals);
            preview.add(new AvailabilityDtos.Day(date, mode, custom, intervalDtos(intervals),
                    before == null ? null : before.version()));
        }
        return new DayPlan(days, preview, impact(userId, overrides, days.size(), List.of()));
    }

    private Impact impact(UUID userId, Map<LocalDate, List<MinuteInterval>> overrides, int recalculated,
            List<LocalDate> clipped) {
        List<AssignedOccurrence> released = new ArrayList<>();
        int retained = 0;
        Instant now = clock.instant();
        for (AssignedOccurrence occurrence : repository.findAssignedOccurrences(userId)) {
            boolean covered = calculator.covers(date -> overrides.containsKey(date) ? overrides.get(date)
                    : repository.findDay(userId, date).map(Day::intervals).orElse(List.of()),
                    occurrence.startsAt(), occurrence.endsAt(), KST);
            if (!covered) {
                if (occurrence.startsAt().isAfter(now)) released.add(occurrence); else retained++;
            }
        }
        List<UUID> visible = released.stream().filter(AssignedOccurrence::groupAccessible)
                .map(AssignedOccurrence::id).toList();
        return new Impact(recalculated, List.copyOf(clipped), List.copyOf(released), visible, retained, List.of());
    }

    private void releaseOccurrences(UUID userId, List<AssignedOccurrence> occurrences, UUID requestId) {
        Instant now = clock.instant();
        for (AssignedOccurrence occurrence : occurrences) {
            if (!repository.release(occurrence.id(), occurrence.version())) throw stale();
            UUID handoff = repository.openAvailabilityHandoff(occurrence.groupId(), occurrence.id(), userId);
            events.audit(occurrence.groupId(), userId, "TASK_UNASSIGNED_AVAILABILITY", "TASK_OCCURRENCE",
                    occurrence.id(), Map.of("assigneeUserId", userId, "version", occurrence.version()),
                    Map.of("assigneeUserId", "", "version", occurrence.version() + 1), requestId);
            if (handoff != null) events.taskNotification(occurrence.groupId(), "HANDOFF_OPEN",
                    "handoff-open:" + handoff, occurrence.id(), handoff, null, occurrence.version() + 1,
                    Map.of("schemaVersion", 1, "reason", "AVAILABILITY"), now);
        }
    }

    private void validateExpectedConfig(UserConfig current, List<WorkPeriod> work, long expectedUser,
            String expectedWork) {
        if (current.version() != expectedUser || !workFingerprint(work).equals(expectedWork))
            throw versionConflict("가능 시간 설정이 변경되었습니다.");
    }

    private void validateExpectedDays(LocalDate from, LocalDate to, List<ExpectedDay> expected,
            Map<LocalDate, Day> current) {
        long count = from.datesUntil(to).count();
        if (expected == null || expected.size() != count) throw validation("expectedDays는 범위의 모든 날짜를 포함해야 합니다.");
        Set<LocalDate> seen = new HashSet<>();
        for (ExpectedDay item : expected) {
            if (item.date() == null || item.date().isBefore(from) || !item.date().isBefore(to) || !seen.add(item.date()))
                throw validation("expectedDays 날짜가 누락되거나 중복되었습니다.");
            Day actual = current.get(item.date());
            Long version = actual == null ? null : actual.version();
            if (!java.util.Objects.equals(version, item.version())) throw versionConflict("날짜별 가능 시간이 변경되었습니다.");
        }
    }

    private void validateMode(String mode, boolean custom, List<AvailabilityDtos.Interval> intervals) {
        if (!Set.of("FULL", "PARTIAL", "UNAVAILABLE").contains(mode)) throw validation("mode가 올바르지 않습니다.");
        List<AvailabilityDtos.Interval> values = intervals == null ? List.of() : intervals;
        if (!"PARTIAL".equals(mode) && (custom || !values.isEmpty()))
            throw validation("FULL/UNAVAILABLE은 customIntervals=false이고 intervals가 비어야 합니다.");
        if ("PARTIAL".equals(mode) && !custom && !values.isEmpty())
            throw validation("자동 계산 PARTIAL에는 intervals를 보낼 수 없습니다.");
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || !from.isBefore(to) || from.plusYears(2).isBefore(to))
            throw validation("날짜 범위가 올바르지 않습니다.");
    }

    private void requireSameState(Claims claims, Object state) {
        if (!claims.stateFingerprint().equals(fingerprints.of(state))) throw stale();
    }

    private AvailabilityDtos.Config configDto(UserConfig config, List<WorkPeriod> work) {
        return new AvailabilityDtos.Config(config.activeStartMinute(), config.activeEndMinute(), config.version(),
                workFingerprint(work), workDtos(work));
    }

    private String workFingerprint(List<WorkPeriod> work) { return fingerprints.of(work); }
    private List<AvailabilityDtos.WorkPeriod> workDtos(List<WorkPeriod> values) { return values.stream().map(value ->
            new AvailabilityDtos.WorkPeriod(value.isoWeekday(), value.startMinute(), value.endMinute())).toList(); }
    private AvailabilityDtos.Day dayDto(LocalDate date, Day day) { return day == null
            ? new AvailabilityDtos.Day(date, null, false, List.of(), null)
            : new AvailabilityDtos.Day(date, day.mode(), day.customIntervals(), intervalDtos(day.intervals()), day.version()); }
    private List<AvailabilityDtos.Interval> intervalDtos(List<MinuteInterval> values) { return values.stream().map(value ->
            new AvailabilityDtos.Interval(value.startMinute(), value.endMinute())).toList(); }
    private MinuteInterval entity(AvailabilityDtos.Interval value) { return new MinuteInterval(value.startMinute(), value.endMinute()); }
    private WorkPeriod entity(AvailabilityDtos.WorkPeriod value) { return new WorkPeriod(value.isoWeekday(), value.startMinute(), value.endMinute()); }
    private UserConfig requireConfig(UUID userId, boolean lock) { return repository.findConfig(userId, lock)
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "활성 사용자가 아닙니다.")); }
    private OffsetDateTime atKst(Instant value) { return value.atZone(KST).toOffsetDateTime(); }
    private Object configCore(long version, String workVersion, ConfigPatch patch) { return Map.of(
            "expectedUserVersion", version, "expectedWorkConfigVersion", workVersion, "patch", patch); }
    private Object daysCore(LocalDate from, LocalDate to, String mode, boolean custom,
            List<AvailabilityDtos.Interval> intervals, List<ExpectedDay> expected) {
        Map<String,Object> value = new LinkedHashMap<>(); value.put("fromDate", from); value.put("toDateExclusive", to);
        value.put("mode", mode); value.put("customIntervals", custom); value.put("intervals", intervals == null ? List.of() : intervals);
        value.put("expectedDays", expected); return value;
    }
    private ApiException validation(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }
    private ApiException versionConflict(String message) { return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", message); }
    private ApiException stale() { return new ApiException(HttpStatus.CONFLICT, "PREVIEW_STALE", "미리보기 이후 상태가 변경되었습니다."); }

    private record ProposedConfig(int start, int end, List<WorkPeriod> work) { }
    private record DayChange(Day before, List<MinuteInterval> intervals) { }
    private record PlannedDay(LocalDate date, String mode, boolean custom, List<MinuteInterval> intervals, Day before) { }
    private record DayPlan(List<PlannedDay> days, List<AvailabilityDtos.Day> previewDays, Impact impact) { }
    private record Impact(int recalculatedCount, List<LocalDate> clippedDates,
            List<AssignedOccurrence> released, List<UUID> visibleReleasedIds, int retainedPastCount,
            List<DayChange> dayChanges) {
        private Object state(Long userVersion, String workVersion) {
            Map<String,Object> value = new LinkedHashMap<>(); value.put("userVersion", userVersion);
            value.put("workVersion", workVersion); value.put("released", released.stream().map(item ->
                    List.of(item.id(), item.version(), item.startsAt(), item.endsAt())).toList());
            value.put("retainedPastCount", retainedPastCount); value.put("clippedDates", clippedDates);
            value.put("dayChanges", dayChanges.stream().map(item -> List.of(item.before().id(),
                    item.before().version(), item.intervals())).toList());
            return value;
        }
    }
}
