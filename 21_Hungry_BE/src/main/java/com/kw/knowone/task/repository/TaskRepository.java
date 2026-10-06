package com.kw.knowone.task.repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.kw.knowone.common.web.CursorService;
import com.kw.knowone.task.entity.TaskModels.Audit;
import com.kw.knowone.task.entity.TaskModels.Candidate;
import com.kw.knowone.task.entity.TaskModels.Handoff;
import com.kw.knowone.task.entity.TaskModels.Occurrence;
import com.kw.knowone.task.entity.TaskModels.Series;
import com.kw.knowone.task.dto.TaskDtos;

@Repository
public class TaskRepository {
    private static final String OCCURRENCE_SELECT = """
            SELECT o.id,o.group_id,o.series_id,s.version AS series_version,o.revision_no,o.anchor_date,s.kind,
                   o.title,o.description,o.starts_at,o.ends_at,o.status,o.assignee_user_id,au.display_name assignee_name,
                   o.assignment_origin,o.is_override,o.completed_by,cu.display_name completed_name,
                   o.performed_by,pu.display_name performed_name,o.completed_at,o.cancel_reason,o.canceled_at,o.version
            FROM task_occurrence o JOIN task_series s ON s.id=o.series_id
            LEFT JOIN app_user au ON au.id=o.assignee_user_id
            LEFT JOIN app_user cu ON cu.id=o.completed_by LEFT JOIN app_user pu ON pu.id=o.performed_by
            """;
    private final JdbcTemplate jdbc;
    public TaskRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Occurrence> findOccurrence(UUID id) {
        return jdbc.query(OCCURRENCE_SELECT + " WHERE o.id=?", this::mapOccurrence, id).stream().findFirst();
    }
    public Optional<Series> findSeries(UUID id) {
        return jdbc.query("""
                SELECT s.id,s.group_id,s.kind,r.title,r.description,r.recurrence,r.first_date,r.last_date,
                       r.weekdays,r.local_time,r.duration_minutes,s.stop_from_date,s.current_revision_no,s.version
                FROM task_series s JOIN task_series_revision r
                  ON r.series_id=s.id AND r.revision_no=s.current_revision_no WHERE s.id=?
                """, this::mapSeries, id).stream().findFirst();
    }
    public List<TaskDtos.Medication> occurrenceMedications(UUID occurrenceId) {
        return jdbc.query("""
                SELECT m.id,m.name,m.dose_text,m.frequency_text,m.starts_on,m.ends_on,m.instructions,
                       m.confirmed_by,u.display_name,m.confirmed_at,m.supersedes_id
                FROM occurrence_medication om JOIN medication_order m ON m.id=om.medication_id
                JOIN app_user u ON u.id=m.confirmed_by WHERE om.occurrence_id=? ORDER BY m.name,m.id
                """, this::mapMedication, occurrenceId);
    }
    public List<TaskDtos.Medication> seriesMedications(UUID seriesId, int revisionNo) {
        return jdbc.query("""
                SELECT m.id,m.name,m.dose_text,m.frequency_text,m.starts_on,m.ends_on,m.instructions,
                       m.confirmed_by,u.display_name,m.confirmed_at,m.supersedes_id
                FROM series_medication sm JOIN medication_order m ON m.id=sm.medication_id
                JOIN app_user u ON u.id=m.confirmed_by WHERE sm.series_id=? AND sm.revision_no=? ORDER BY m.name,m.id
                """, this::mapMedication, seriesId, revisionNo);
    }
    public List<UUID> sourceEncounterIds(UUID seriesId, UUID occurrenceId) {
        return jdbc.query("""
                SELECT encounter_id FROM (
                  SELECT x.encounter_id FROM task_series s JOIN extracted_item x ON x.id=s.source_item_id WHERE s.id=?
                  UNION
                  SELECT x.encounter_id FROM occurrence_medication om JOIN medication_order m ON m.id=om.medication_id
                    JOIN extracted_item x ON x.id=m.source_item_id WHERE om.occurrence_id=?
                ) linked ORDER BY encounter_id
                """, (rs,n)->rs.getObject(1,UUID.class), seriesId, occurrenceId);
    }
    public Optional<UUID> findEncounterGroup(UUID encounterId) {
        return jdbc.query("SELECT group_id FROM encounter WHERE id=? AND deleted_at IS NULL",
                (rs,n)->rs.getObject(1,UUID.class), encounterId).stream().findFirst();
    }
    public List<Occurrence> list(UUID groupId, Instant from, Instant to, String status, UUID assignee,
            boolean unassigned, boolean overdue, UUID encounterId, int fetch, CursorService.Value cursor, Instant now) {
        StringBuilder sql = new StringBuilder(OCCURRENCE_SELECT).append(" WHERE o.group_id=? AND o.starts_at>=? AND o.starts_at<?");
        List<Object> args = new ArrayList<>(List.of(groupId, Timestamp.from(from), Timestamp.from(to)));
        if (status == null) sql.append(" AND o.status IN ('PENDING','COMPLETED')");
        else { sql.append(" AND o.status=?"); args.add(status); }
        if (assignee != null) { sql.append(" AND o.assignee_user_id=?"); args.add(assignee); }
        if (unassigned) sql.append(" AND o.assignee_user_id IS NULL");
        if (overdue) { sql.append(" AND o.status='PENDING' AND o.ends_at<=?"); args.add(Timestamp.from(now)); }
        if (encounterId != null) { sql.append(" AND EXISTS (SELECT 1 FROM extracted_item x WHERE x.id=s.source_item_id AND x.encounter_id=?)"); args.add(encounterId); }
        if (cursor != null) { sql.append(" AND (o.starts_at,o.id)>(?,?)"); args.add(Timestamp.from(cursor.time())); args.add(cursor.id()); }
        sql.append(" ORDER BY o.starts_at,o.id LIMIT ?"); args.add(fetch);
        return jdbc.query(sql.toString(), this::mapOccurrence, args.toArray());
    }
    public UUID insertSeries(UUID groupId, UUID actor, String kind, String generationKey) {
        UUID id=UUID.randomUUID(); jdbc.update("""
                INSERT INTO task_series(id,group_id,kind,created_by,generation_key) VALUES (?,?,?,?,?)
                """, id,groupId,kind,actor,generationKey); return id;
    }
    public void insertRevision(UUID seriesId, UUID groupId, String title, String description, LocalDate date,
            LocalTime time, int duration, UUID actor, Instant now) {
        jdbc.update("""
                INSERT INTO task_series_revision(series_id,revision_no,group_id,title,description,recurrence,
                  first_date,last_date,weekdays,local_time,duration_minutes,effective_at,changed_by)
                VALUES (?,1,?,?,?,'ONCE',?,?,ARRAY[]::smallint[],?,?,?,?)
                """, seriesId,groupId,title,description,date,date,time,duration,Timestamp.from(now),actor);
    }
    public UUID insertOccurrence(UUID groupId, UUID seriesId, LocalDate date, String title, String description,
            Instant starts, Instant ends, UUID assignee) {
        UUID id=UUID.randomUUID(); jdbc.update("""
                INSERT INTO task_occurrence(id,group_id,series_id,revision_no,anchor_date,title,description,
                  starts_at,ends_at,assignee_user_id,assignment_origin)
                VALUES (?,?,?,1,?,?,?,?,?,?,?)
                """, id,groupId,seriesId,date,title,description,Timestamp.from(starts),Timestamp.from(ends),assignee,
                assignee == null ? null : "AUTO"); return id;
    }
    public List<Candidate> candidates(UUID groupId) {
        return jdbc.query("""
                SELECT m.id,m.user_id,m.priority FROM group_member m JOIN app_user u ON u.id=m.user_id
                WHERE m.group_id=? AND m.status='ACTIVE' AND m.role='CAREGIVER' AND u.status='ACTIVE'
                ORDER BY m.priority,m.id
                """, (rs,n)->new Candidate(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getInt(3)),groupId);
    }
    public int weeklyCount(UUID groupId, UUID userId, Instant weekStart, Instant weekEnd) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM task_occurrence WHERE group_id=? AND assignee_user_id=? AND status<>'CANCELED'
                  AND starts_at>=? AND starts_at<?
                """, Integer.class,groupId,userId,Timestamp.from(weekStart),Timestamp.from(weekEnd));
    }
    public UUID openNoCandidate(UUID groupId, UUID occurrenceId) {
        UUID id=UUID.randomUUID(); int changed=jdbc.update("""
                INSERT INTO handoff_request(id,group_id,occurrence_id,reason,status)
                SELECT ?,?,?,'NO_CANDIDATE','OPEN' WHERE NOT EXISTS
                  (SELECT 1 FROM handoff_request WHERE occurrence_id=? AND status='OPEN')
                """,id,groupId,occurrenceId,occurrenceId); return changed==1?id:null;
    }
    public Optional<Handoff> findOpenHandoff(UUID occurrenceId) {
        return jdbc.query("""
                SELECT h.id,h.occurrence_id,h.reason,h.previous_assignee_id,prev.display_name,
                       h.requested_by,req.display_name,h.status,h.accepted_by,acc.display_name,
                       h.closed_at,h.close_reason,h.version
                FROM handoff_request h LEFT JOIN app_user prev ON prev.id=h.previous_assignee_id
                LEFT JOIN app_user req ON req.id=h.requested_by LEFT JOIN app_user acc ON acc.id=h.accepted_by
                WHERE h.occurrence_id=? AND h.status='OPEN'
                """,this::mapHandoff,occurrenceId).stream().findFirst();
    }
    public int assign(UUID id,long version,UUID assignee) { return jdbc.update("""
            UPDATE task_occurrence SET assignee_user_id=?,assignment_origin='MANUAL',version=version+1
            WHERE id=? AND version=? AND status='PENDING'
            """,assignee,id,version); }
    public void acceptOpenHandoff(UUID occurrenceId, UUID assignee, Instant now) { jdbc.update("""
            UPDATE handoff_request SET status='ACCEPTED',accepted_by=?,closed_at=?,version=version+1
            WHERE occurrence_id=? AND status='OPEN'
            """,assignee,Timestamp.from(now),occurrenceId); }
    public int complete(UUID id,long version,UUID completedBy,UUID performedBy,Instant now) { return jdbc.update("""
            UPDATE task_occurrence SET status='COMPLETED',completed_by=?,performed_by=?,completed_at=?,version=version+1
            WHERE id=? AND version=? AND status='PENDING'
            """,completedBy,performedBy,Timestamp.from(now),id,version); }
    public void closeOpenHandoff(UUID occurrenceId,String reason,Instant now) { jdbc.update("""
            UPDATE handoff_request SET status='CLOSED',closed_at=?,close_reason=?,version=version+1
            WHERE occurrence_id=? AND status='OPEN'
            """,Timestamp.from(now),reason,occurrenceId); }
    public void cancelPendingNotifications(UUID occurrenceId) { jdbc.update("""
            UPDATE notification_event SET status='CANCELED'
            WHERE occurrence_id=? AND status IN ('PENDING','FAILED')
            """,occurrenceId); }
    public int reopen(UUID id,long version,boolean keepAssignee) { return jdbc.update("""
            UPDATE task_occurrence SET status='PENDING',completed_by=NULL,performed_by=NULL,completed_at=NULL,
              assignee_user_id=CASE WHEN ? THEN assignee_user_id ELSE NULL END,
              assignment_origin=CASE WHEN ? THEN assignment_origin ELSE NULL END,version=version+1
            WHERE id=? AND version=? AND status='COMPLETED'
            """,keepAssignee,keepAssignee,id,version); }
    public List<Audit> history(UUID groupId,UUID occurrenceId,int fetch,CursorService.Value cursor) {
        String sql="""
                SELECT a.id,a.event_type,a.actor_user_id,u.display_name,a.before_data::text,a.after_data::text,a.created_at
                FROM audit_event a LEFT JOIN app_user u ON u.id=a.actor_user_id
                WHERE a.group_id=? AND a.entity_type='TASK_OCCURRENCE' AND a.entity_id=?
                """;
        List<Object> args=new ArrayList<>(List.of(groupId,occurrenceId));
        if(cursor!=null){sql+=" AND (a.created_at,a.id)<(?,?)";args.add(Timestamp.from(cursor.time()));args.add(cursor.id());}
        sql+=" ORDER BY a.created_at DESC,a.id DESC LIMIT ?";args.add(fetch);
        return jdbc.query(sql,this::mapAudit,args.toArray());
    }

    private Occurrence mapOccurrence(ResultSet r,int n)throws SQLException{return new Occurrence(
            r.getObject("id",UUID.class),r.getObject("group_id",UUID.class),r.getObject("series_id",UUID.class),
            r.getLong("series_version"),r.getInt("revision_no"),r.getObject("anchor_date",LocalDate.class),r.getString("kind"),
            r.getString("title"),r.getString("description"),r.getTimestamp("starts_at").toInstant(),r.getTimestamp("ends_at").toInstant(),
            r.getString("status"),r.getObject("assignee_user_id",UUID.class),r.getString("assignee_name"),r.getString("assignment_origin"),
            r.getBoolean("is_override"),r.getObject("completed_by",UUID.class),r.getString("completed_name"),
            r.getObject("performed_by",UUID.class),r.getString("performed_name"),instant(r,"completed_at"),r.getString("cancel_reason"),
            instant(r,"canceled_at"),r.getLong("version"));}
    private Series mapSeries(ResultSet r,int n)throws SQLException{Array a=r.getArray("weekdays");Object[] raw=a==null?new Object[0]:(Object[])a.getArray();List<Integer> weekdays=java.util.Arrays.stream(raw).map(value->((Number)value).intValue()).toList();return new Series(
            r.getObject("id",UUID.class),r.getObject("group_id",UUID.class),r.getString("kind"),r.getString("title"),r.getString("description"),
            r.getString("recurrence"),r.getObject("first_date",LocalDate.class),r.getObject("last_date",LocalDate.class),weekdays,
            r.getObject("local_time",LocalTime.class),r.getInt("duration_minutes"),r.getObject("stop_from_date",LocalDate.class),
            r.getInt("current_revision_no"),r.getLong("version"));}
    private Handoff mapHandoff(ResultSet r,int n)throws SQLException{return new Handoff(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),
            r.getObject(4,UUID.class),r.getString(5),r.getObject(6,UUID.class),r.getString(7),r.getString(8),r.getObject(9,UUID.class),
            r.getString(10),instant(r,"closed_at"),r.getString(12),r.getLong(13));}
    private Audit mapAudit(ResultSet r,int n)throws SQLException{return new Audit(r.getObject(1,UUID.class),r.getString(2),r.getObject(3,UUID.class),
            r.getString(4),r.getString(5),r.getString(6),r.getTimestamp(7).toInstant());}
    private TaskDtos.Medication mapMedication(ResultSet r,int n)throws SQLException{return new TaskDtos.Medication(
            r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4),r.getObject(5,LocalDate.class),
            r.getObject(6,LocalDate.class),r.getString(7),new TaskDtos.UserRef(r.getObject(8,UUID.class),r.getString(9)),
            r.getTimestamp(10).toInstant().atZone(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime(),r.getObject(11,UUID.class));}
    private Instant instant(ResultSet r,String name)throws SQLException{Timestamp value=r.getTimestamp(name);return value==null?null:value.toInstant();}
}
