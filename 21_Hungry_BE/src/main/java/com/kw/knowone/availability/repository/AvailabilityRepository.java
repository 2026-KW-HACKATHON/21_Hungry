package com.kw.knowone.availability.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;
import com.kw.knowone.availability.entity.AvailabilityModels.AssignedOccurrence;
import com.kw.knowone.availability.entity.AvailabilityModels.Day;
import com.kw.knowone.availability.entity.AvailabilityModels.MinuteInterval;
import com.kw.knowone.availability.entity.AvailabilityModels.UserConfig;
import com.kw.knowone.availability.entity.AvailabilityModels.WorkPeriod;

@Repository
public class AvailabilityRepository {
    private final JdbcTemplate jdbc;

    public AvailabilityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserConfig> findConfig(UUID userId, boolean lock) {
        String sql = "SELECT id, active_start_minute, active_end_minute, version FROM app_user "
                + "WHERE id = ? AND status = 'ACTIVE'" + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, n) -> new UserConfig(rs.getObject(1, UUID.class), rs.getInt(2), rs.getInt(3),
                rs.getLong(4)), userId).stream().findFirst();
    }

    public List<WorkPeriod> findWorkPeriods(UUID userId) {
        return jdbc.query("""
                SELECT iso_weekday, start_minute, end_minute FROM weekly_work_period
                WHERE user_id = ? ORDER BY iso_weekday, start_minute, end_minute
                """, (rs, n) -> new WorkPeriod(rs.getInt(1), rs.getInt(2), rs.getInt(3)), userId);
    }

    public Map<LocalDate, Day> findDays(UUID userId, LocalDate from, LocalDate toExclusive) {
        List<Day> rows = jdbc.query("""
                SELECT d.id, d.user_id, d.local_date, d.mode, d.custom_intervals, d.version,
                       i.start_minute, i.end_minute
                FROM availability_day d
                LEFT JOIN availability_interval i ON i.day_id = d.id
                WHERE d.user_id = ? AND d.local_date >= ? AND d.local_date < ?
                ORDER BY d.local_date, i.start_minute, i.end_minute
                """, (ResultSetExtractor<List<Day>>) this::mapDays, userId, from, toExclusive);
        Map<LocalDate, Day> result = new LinkedHashMap<>();
        for (Day day : rows) result.put(day.date(), day);
        return result;
    }

    public Optional<Day> findDay(UUID userId, LocalDate date) {
        return Optional.ofNullable(findDays(userId, date, date.plusDays(1)).get(date));
    }

    public int updateConfig(UUID userId, long expectedVersion, int start, int end) {
        return jdbc.update("""
                UPDATE app_user SET active_start_minute = ?, active_end_minute = ?, version = version + 1
                WHERE id = ? AND version = ? AND status = 'ACTIVE'
                """, start, end, userId, expectedVersion);
    }

    public void replaceWorkPeriods(UUID userId, List<WorkPeriod> periods) {
        jdbc.update("DELETE FROM weekly_work_period WHERE user_id = ?", userId);
        for (WorkPeriod period : periods) {
            jdbc.update("""
                    INSERT INTO weekly_work_period(id,user_id,iso_weekday,start_minute,end_minute)
                    VALUES (?,?,?,?,?)
                    """, UUID.randomUUID(), userId, period.isoWeekday(), period.startMinute(), period.endMinute());
        }
    }

    public Day insertDay(UUID userId, LocalDate date, String mode, boolean custom,
            List<MinuteInterval> intervals) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO availability_day(id,user_id,local_date,mode,custom_intervals)
                VALUES (?,?,?,?,?)
                """, id, userId, date, mode, custom);
        replaceIntervals(id, intervals);
        return findDay(userId, date).orElseThrow();
    }

    public Day updateDay(Day before, String mode, boolean custom, List<MinuteInterval> intervals) {
        int changed = jdbc.update("""
                UPDATE availability_day SET mode=?, custom_intervals=?, version=version+1
                WHERE id=? AND version=?
                """, mode, custom, before.id(), before.version());
        if (changed != 1) throw new IllegalStateException("Concurrent availability day update");
        replaceIntervals(before.id(), intervals);
        return findDay(before.userId(), before.date()).orElseThrow();
    }

    public void replaceIntervals(UUID dayId, List<MinuteInterval> intervals) {
        jdbc.update("DELETE FROM availability_interval WHERE day_id = ?", dayId);
        for (MinuteInterval interval : intervals) {
            jdbc.update("""
                    INSERT INTO availability_interval(id,day_id,start_minute,end_minute) VALUES (?,?,?,?)
                    """, UUID.randomUUID(), dayId, interval.startMinute(), interval.endMinute());
        }
    }

    public List<AssignedOccurrence> findAssignedOccurrences(UUID userId) {
        return jdbc.query("""
                SELECT o.id,o.group_id,o.starts_at,o.ends_at,o.version,
                       EXISTS (SELECT 1 FROM group_member mine WHERE mine.group_id=o.group_id
                               AND mine.user_id=? AND mine.status='ACTIVE') AS accessible
                FROM task_occurrence o
                WHERE o.assignee_user_id=? AND o.status <> 'CANCELED'
                ORDER BY o.starts_at,o.id
                """, (rs, n) -> new AssignedOccurrence(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant(), rs.getLong(5),
                        rs.getBoolean(6)), userId, userId);
    }

    public boolean release(UUID occurrenceId, long version) {
        boolean changed = jdbc.update("""
                UPDATE task_occurrence SET assignee_user_id=NULL, assignment_origin=NULL, version=version+1
                WHERE id=? AND version=? AND assignee_user_id IS NOT NULL AND status <> 'CANCELED'
                """, occurrenceId, version) == 1;
        if (changed) jdbc.update("""
                UPDATE notification_event SET status='CANCELED'
                WHERE occurrence_id=? AND status IN ('PENDING','FAILED')
                """, occurrenceId);
        return changed;
    }

    public UUID openAvailabilityHandoff(UUID groupId, UUID occurrenceId, UUID previousAssignee) {
        UUID id = UUID.randomUUID();
        int changed = jdbc.update("""
                INSERT INTO handoff_request(id,group_id,occurrence_id,reason,previous_assignee_id,status)
                SELECT ?,?,?, 'AVAILABILITY',?, 'OPEN'
                WHERE NOT EXISTS (SELECT 1 FROM handoff_request WHERE occurrence_id=? AND status='OPEN')
                """, id, groupId, occurrenceId, previousAssignee, occurrenceId);
        return changed == 1 ? id : null;
    }

    public boolean hasConflict(UUID userId, Instant startsAt, Instant endsAt, UUID excludedOccurrenceId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM task_occurrence
                WHERE assignee_user_id=? AND status <> 'CANCELED'
                  AND starts_at < ? AND ends_at > ?
                  AND (?::uuid IS NULL OR id <> ?::uuid)
                """, Integer.class, userId, Timestamp.from(endsAt), Timestamp.from(startsAt),
                excludedOccurrenceId, excludedOccurrenceId);
        return count != null && count > 0;
    }

    private List<Day> mapDays(ResultSet rs) throws SQLException {
        Map<UUID, MutableDay> grouped = new LinkedHashMap<>();
        while (rs.next()) {
            UUID id = rs.getObject("id", UUID.class);
            MutableDay row = grouped.computeIfAbsent(id, ignored -> new MutableDay(id,
                    rsUuid(rs, "user_id"), rsDate(rs, "local_date"), rsString(rs, "mode"),
                    rsBoolean(rs, "custom_intervals"), rsLong(rs, "version")));
            Integer start = rs.getObject("start_minute", Integer.class);
            if (start != null) row.intervals.add(new MinuteInterval(start, rs.getInt("end_minute")));
        }
        return grouped.values().stream().map(MutableDay::freeze).toList();
    }

    private UUID rsUuid(ResultSet rs, String name) { try { return rs.getObject(name, UUID.class); } catch (SQLException e) { throw new IllegalStateException(e); } }
    private LocalDate rsDate(ResultSet rs, String name) { try { return rs.getObject(name, LocalDate.class); } catch (SQLException e) { throw new IllegalStateException(e); } }
    private String rsString(ResultSet rs, String name) { try { return rs.getString(name); } catch (SQLException e) { throw new IllegalStateException(e); } }
    private boolean rsBoolean(ResultSet rs, String name) { try { return rs.getBoolean(name); } catch (SQLException e) { throw new IllegalStateException(e); } }
    private long rsLong(ResultSet rs, String name) { try { return rs.getLong(name); } catch (SQLException e) { throw new IllegalStateException(e); } }

    private static final class MutableDay {
        private final UUID id; private final UUID userId; private final LocalDate date; private final String mode;
        private final boolean custom; private final long version; private final java.util.ArrayList<MinuteInterval> intervals = new java.util.ArrayList<>();
        private MutableDay(UUID id, UUID userId, LocalDate date, String mode, boolean custom, long version) {
            this.id=id; this.userId=userId; this.date=date; this.mode=mode; this.custom=custom; this.version=version;
        }
        private Day freeze() { return new Day(id,userId,date,mode,custom,version,List.copyOf(intervals)); }
    }
}
