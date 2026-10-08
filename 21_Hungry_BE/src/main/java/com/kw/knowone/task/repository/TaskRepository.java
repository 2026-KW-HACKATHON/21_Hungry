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
                SELECT s.id,s.group_id,s.created_by,s.creation_origin,s.kind,r.title,r.description,r.recurrence,r.first_date,r.last_date,
                       r.weekdays,r.local_time,r.duration_minutes,s.stop_from_date,s.current_revision_no,s.version
                FROM task_series s JOIN task_series_revision r
                  ON r.series_id=s.id AND r.revision_no=s.current_revision_no WHERE s.id=?
                """, this::mapSeries, id).stream().findFirst();
    }
    public Optional<Series> findMedicationSeries(UUID groupId,String recurrence,List<Integer> weekdays,LocalTime time,int duration){String array="{"+weekdays.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","))+"}";return jdbc.query("""
            SELECT s.id,s.group_id,s.created_by,s.creation_origin,s.kind,r.title,r.description,r.recurrence,r.first_date,r.last_date,
                   r.weekdays,r.local_time,r.duration_minutes,s.stop_from_date,s.current_revision_no,s.version
            FROM task_series s JOIN task_series_revision r ON r.series_id=s.id AND r.revision_no=s.current_revision_no
            WHERE s.group_id=? AND s.kind='MEDICATION' AND r.recurrence=? AND r.weekdays=?::smallint[]
              AND r.local_time=? AND r.duration_minutes=? AND s.stop_from_date IS NULL
            ORDER BY s.created_at,s.id LIMIT 1
            """,this::mapSeries,groupId,recurrence,array,time,duration).stream().findFirst();}
    public List<Series> findGeneratableSeries() {
        return jdbc.query("""
                SELECT s.id,s.group_id,s.created_by,s.creation_origin,s.kind,r.title,r.description,r.recurrence,r.first_date,r.last_date,
                       r.weekdays,r.local_time,r.duration_minutes,s.stop_from_date,s.current_revision_no,s.version
                FROM task_series s JOIN care_group g ON g.id=s.group_id AND g.status='ACTIVE'
                JOIN task_series_revision r ON r.series_id=s.id AND r.revision_no=s.current_revision_no
                ORDER BY s.group_id,s.id
                """, this::mapSeries);
    }
    public List<Occurrence> findSeriesOccurrences(UUID seriesId) {
        return jdbc.query(OCCURRENCE_SELECT + " WHERE o.series_id=? ORDER BY o.anchor_date,o.id",
                this::mapOccurrence, seriesId);
    }
    public List<Occurrence> findFutureSeriesOccurrences(UUID seriesId, Instant cutoff) {
        return jdbc.query(OCCURRENCE_SELECT +
                " WHERE o.series_id=? AND o.starts_at>=? ORDER BY o.anchor_date,o.id",
                this::mapOccurrence, seriesId, Timestamp.from(cutoff));
    }
    public Optional<Occurrence> findBySeriesAnchor(UUID seriesId, LocalDate anchorDate) {
        return jdbc.query(OCCURRENCE_SELECT + " WHERE o.series_id=? AND o.anchor_date=?",
                this::mapOccurrence, seriesId, anchorDate).stream().findFirst();
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
    public List<RankedOccurrence> listDate(UUID groupId,UUID userId,Instant from,Instant to,boolean mineOnly,
            Integer cursorRank,Instant cursorTime,UUID cursorId,int fetch){
        String rank="""
          CASE WHEN o.status='PENDING' AND o.assignee_user_id IS NULL THEN
            CASE WHEN EXISTS(SELECT 1 FROM handoff_response hr WHERE hr.user_id=? AND hr.handoff_id=(
              SELECT h.id FROM handoff_request h WHERE h.occurrence_id=o.id ORDER BY h.created_at DESC,h.id DESC LIMIT 1
            )) THEN 1 ELSE 0 END ELSE 2 END
          """;
        String baseRank=rank.replace("o.","base.");
        StringBuilder sql=new StringBuilder("SELECT base.*, ").append(baseRank).append(" sort_rank FROM (")
                .append(OCCURRENCE_SELECT).append(" WHERE o.group_id=? AND o.starts_at>=? AND o.starts_at<? AND o.status<>'CANCELED'");
        List<Object> args=new ArrayList<>();args.add(userId);args.add(groupId);args.add(Timestamp.from(from));args.add(Timestamp.from(to));
        if(mineOnly){sql.append(" AND o.assignee_user_id=?");args.add(userId);}sql.append(") base");
        if(cursorRank!=null){sql.append(" WHERE (").append(baseRank).append(",base.starts_at,base.id)>(?,?,?)");args.add(userId);args.add(cursorRank);args.add(Timestamp.from(cursorTime));args.add(cursorId);}
        sql.append(" ORDER BY sort_rank,base.starts_at,base.id LIMIT ?");args.add(fetch);
        return jdbc.query(sql.toString(),(rs,n)->new RankedOccurrence(mapOccurrence(rs,n),rs.getInt("sort_rank")),args.toArray());
    }

    public List<CalendarCount> calendar(UUID groupId,Instant from,Instant to){return jdbc.query("""
            SELECT (o.starts_at AT TIME ZONE 'Asia/Seoul')::date local_date,count(*) total_count,
              count(*) FILTER(WHERE o.status='COMPLETED') completed_count,
              count(*) FILTER(WHERE o.status='PENDING' AND o.assignee_user_id IS NULL) unassigned_count
            FROM task_occurrence o WHERE o.group_id=? AND o.starts_at>=? AND o.starts_at<? AND o.status<>'CANCELED'
            GROUP BY local_date ORDER BY local_date
            """,(rs,n)->new CalendarCount(rs.getObject(1,LocalDate.class),rs.getLong(2),rs.getLong(3),rs.getLong(4)),
            groupId,Timestamp.from(from),Timestamp.from(to));}
    public List<Occurrence> linkedToEncounter(UUID encounterId,int fetch){return jdbc.query(OCCURRENCE_SELECT+"""
            WHERE EXISTS(SELECT 1 FROM extracted_item x WHERE x.id=s.source_item_id AND x.encounter_id=?)
               OR EXISTS(SELECT 1 FROM occurrence_medication om JOIN medication_order m ON m.id=om.medication_id
                         JOIN extracted_item x ON x.id=m.source_item_id WHERE om.occurrence_id=o.id AND x.encounter_id=?)
            ORDER BY o.starts_at,o.id LIMIT ?
            """,this::mapOccurrence,encounterId,encounterId,fetch);}
    public UUID insertSeries(UUID groupId, UUID actor, String kind, String generationKey) {
        String origin=generationKey.startsWith("manual:")?"MANUAL":generationKey.startsWith("review:")?"REVIEW":"AI";
        UUID id=UUID.randomUUID(); jdbc.update("""
                INSERT INTO task_series(id,group_id,kind,created_by,generation_key,creation_origin) VALUES (?,?,?,?,?,?)
                """, id,groupId,kind,actor,generationKey,origin); return id;
    }
    public UUID insertSourcedSeries(UUID groupId,UUID actor,String kind,String generationKey,UUID sourceItemId){
        UUID id=UUID.randomUUID();jdbc.update("""
                INSERT INTO task_series(id,group_id,kind,created_by,generation_key,source_item_id,creation_origin) VALUES (?,?,?,?,?,?,'AI')
                """,id,groupId,kind,actor,generationKey,sourceItemId);return id;}
    public void linkSeriesMedication(UUID groupId,UUID seriesId,int revisionNo,UUID medicationId){jdbc.update("""
            INSERT INTO series_medication(group_id,series_id,revision_no,medication_id) VALUES (?,?,?,?) ON CONFLICT DO NOTHING
            """,groupId,seriesId,revisionNo,medicationId);}
    public void copySeriesMedications(UUID groupId,UUID seriesId,int fromRevision,int toRevision){jdbc.update("""
            INSERT INTO series_medication(group_id,series_id,revision_no,medication_id)
            SELECT group_id,series_id,?,medication_id FROM series_medication WHERE group_id=? AND series_id=? AND revision_no=?
            ON CONFLICT DO NOTHING
            """,toRevision,groupId,seriesId,fromRevision);}
    public List<UUID> activeSeriesMedicationIds(UUID seriesId,int revisionNo,LocalDate date){return jdbc.query("""
            SELECT m.id FROM series_medication sm JOIN medication_order m ON m.id=sm.medication_id
            WHERE sm.series_id=? AND sm.revision_no=? AND m.starts_on<=? AND m.ends_on>=?
              AND NOT EXISTS(SELECT 1 FROM medication_order newer WHERE newer.supersedes_id=m.id AND newer.starts_on<=?)
            ORDER BY m.id
            """,(r,n)->r.getObject(1,UUID.class),seriesId,revisionNo,date,date,date);}
    public void snapshotOccurrenceMedications(UUID groupId,UUID occurrenceId,List<UUID> medicationIds){for(UUID id:medicationIds)jdbc.update("""
            INSERT INTO occurrence_medication(group_id,occurrence_id,medication_id) VALUES (?,?,?) ON CONFLICT DO NOTHING
            """,groupId,occurrenceId,id);}
    public void refreshFutureMedicationSnapshots(UUID groupId,UUID seriesId,int revisionNo,Instant cutoff){jdbc.update("""
            UPDATE task_occurrence SET revision_no=? WHERE series_id=? AND status='PENDING' AND starts_at>=? AND is_override=false
            """,revisionNo,seriesId,Timestamp.from(cutoff));jdbc.update("""
            DELETE FROM occurrence_medication om USING task_occurrence o
            WHERE om.occurrence_id=o.id AND o.series_id=? AND o.status='PENDING' AND o.starts_at>=?
              AND NOT EXISTS(SELECT 1 FROM series_medication sm JOIN medication_order m ON m.id=sm.medication_id
                WHERE sm.series_id=o.series_id AND sm.revision_no=? AND m.id=om.medication_id AND m.starts_on<=o.anchor_date AND m.ends_on>=o.anchor_date
                  AND NOT EXISTS(SELECT 1 FROM medication_order newer WHERE newer.supersedes_id=m.id AND newer.starts_on<=o.anchor_date))
            """,seriesId,Timestamp.from(cutoff),revisionNo);jdbc.update("""
            INSERT INTO occurrence_medication(group_id,occurrence_id,medication_id)
            SELECT ?,o.id,m.id FROM task_occurrence o JOIN series_medication sm ON sm.series_id=o.series_id AND sm.revision_no=?
              JOIN medication_order m ON m.id=sm.medication_id
            WHERE o.series_id=? AND o.status='PENDING' AND o.starts_at>=? AND m.starts_on<=o.anchor_date AND m.ends_on>=o.anchor_date
              AND NOT EXISTS(SELECT 1 FROM medication_order newer WHERE newer.supersedes_id=m.id AND newer.starts_on<=o.anchor_date)
            ON CONFLICT DO NOTHING
            """,groupId,revisionNo,seriesId,Timestamp.from(cutoff));jdbc.update("""
            UPDATE task_occurrence o SET status='CANCELED',cancel_reason='RULE_CHANGED',canceled_at=now(),assignee_user_id=NULL,assignment_origin=NULL,version=version+1
            WHERE o.series_id=? AND o.status='PENDING' AND o.starts_at>=? AND NOT EXISTS(SELECT 1 FROM occurrence_medication om WHERE om.occurrence_id=o.id)
            """,seriesId,Timestamp.from(cutoff));jdbc.update("UPDATE handoff_request h SET status='CLOSED',closed_at=now(),close_reason='RULE_CHANGED',version=h.version+1 FROM task_occurrence o WHERE o.id=h.occurrence_id AND o.series_id=? AND o.status='CANCELED' AND h.status='OPEN'",seriesId);
        jdbc.update("UPDATE notification_event e SET status='CANCELED',lease_token=NULL,lease_until=NULL FROM task_occurrence o WHERE o.id=e.occurrence_id AND o.series_id=? AND o.status='CANCELED' AND e.status IN ('PENDING','FAILED','RUNNING')",seriesId);}
    public List<TaskDtos.Medication> medicationsByIds(UUID groupId,List<UUID> ids){if(ids==null||ids.isEmpty())return List.of();String marks=String.join(",",java.util.Collections.nCopies(ids.size(),"?"));
        java.util.ArrayList<Object> args=new java.util.ArrayList<>();args.add(groupId);args.addAll(ids);return jdbc.query("""
                SELECT m.id,m.name,m.dose_text,m.frequency_text,m.starts_on,m.ends_on,m.instructions,
                       m.confirmed_by,u.display_name,m.confirmed_at,m.supersedes_id
                FROM medication_order m JOIN app_user u ON u.id=m.confirmed_by WHERE m.group_id=? AND m.id IN (%s) ORDER BY m.id
                """.formatted(marks),this::mapMedication,args.toArray());}
    public List<UUID> pendingOccurrenceIdsForMedications(List<UUID> ids,Instant now){if(ids.isEmpty())return List.of();String marks=String.join(",",java.util.Collections.nCopies(ids.size(),"?"));java.util.ArrayList<Object> args=new java.util.ArrayList<>(ids);args.add(Timestamp.from(now));return jdbc.query("""
            SELECT DISTINCT o.id FROM task_occurrence o JOIN occurrence_medication om ON om.occurrence_id=o.id
            WHERE om.medication_id IN (%s) AND o.status='PENDING' AND o.starts_at>=? ORDER BY o.id
            """.formatted(marks),(r,n)->r.getObject(1,UUID.class),args.toArray());}
    public List<Series> seriesForMedication(UUID medicationId){return jdbc.query("""
            SELECT DISTINCT s.id,s.group_id,s.created_by,s.creation_origin,s.kind,r.title,r.description,r.recurrence,r.first_date,r.last_date,
                   r.weekdays,r.local_time,r.duration_minutes,s.stop_from_date,s.current_revision_no,s.version
            FROM task_series s JOIN task_series_revision r ON r.series_id=s.id AND r.revision_no=s.current_revision_no
            JOIN series_medication sm ON sm.series_id=s.id AND sm.revision_no=s.current_revision_no
            WHERE sm.medication_id=? ORDER BY s.id
            """,this::mapSeries,medicationId);}
    public void insertRevision(UUID seriesId, UUID groupId, String title, String description, LocalDate date,
            LocalTime time, int duration, UUID actor, Instant now) {
        jdbc.update("""
                INSERT INTO task_series_revision(series_id,revision_no,group_id,title,description,recurrence,
                  first_date,last_date,weekdays,local_time,duration_minutes,effective_at,changed_by)
                VALUES (?,1,?,?,?,'ONCE',?,?,ARRAY[]::smallint[],?,?,?,?)
                """, seriesId,groupId,title,description,date,date,time,duration,Timestamp.from(now),actor);
    }
    public void insertRevision(UUID seriesId, UUID groupId, int revisionNo, String title, String description,
            String recurrence, LocalDate firstDate, LocalDate lastDate, List<Integer> weekdays,
            LocalTime time, int duration, UUID actor, Instant effectiveAt) {
        jdbc.update("""
                INSERT INTO task_series_revision(series_id,revision_no,group_id,title,description,recurrence,
                  first_date,last_date,weekdays,local_time,duration_minutes,effective_at,changed_by)
                VALUES (?,?,?,?,?,?,?, ?,?::smallint[],?,?,?,?)
                """, seriesId,revisionNo,groupId,title,description,recurrence,firstDate,lastDate,
                "{"+weekdays.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","))+"}",
                time,duration,Timestamp.from(effectiveAt),actor);
    }
    public int advanceSeries(UUID seriesId, long version, int revisionNo) {
        return jdbc.update("UPDATE task_series SET current_revision_no=?,version=version+1 WHERE id=? AND version=?",
                revisionNo,seriesId,version);
    }
    public int stopSeries(UUID seriesId, long version, LocalDate stopDate) {
        return jdbc.update("""
                UPDATE task_series SET stop_from_date=CASE WHEN stop_from_date IS NULL OR stop_from_date>? THEN ? ELSE stop_from_date END,
                  version=version+1 WHERE id=? AND version=?
                """,stopDate,stopDate,seriesId,version);
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
    public UUID insertOccurrenceIfAbsent(UUID groupId, UUID seriesId, int revisionNo, LocalDate date,
            String title, String description, Instant starts, Instant ends) {
        UUID id=UUID.randomUUID();
        return jdbc.query("""
                INSERT INTO task_occurrence(id,group_id,series_id,revision_no,anchor_date,title,description,starts_at,ends_at)
                VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (series_id,anchor_date) DO NOTHING RETURNING id
                """,(rs,n)->rs.getObject(1,UUID.class),id,groupId,seriesId,revisionNo,date,title,description,
                Timestamp.from(starts),Timestamp.from(ends)).stream().findFirst().orElse(null);
    }
    public int assignAuto(UUID occurrenceId, UUID userId) {
        return jdbc.update("""
                UPDATE task_occurrence SET assignee_user_id=?,assignment_origin='AUTO'
                WHERE id=? AND status='PENDING' AND assignee_user_id IS NULL
                """,userId,occurrenceId);
    }
    public int updateOccurrenceOverride(UUID id,long version,String title,String description,Instant starts,Instant ends,
            boolean keepAssignee) {
        return jdbc.update("""
                UPDATE task_occurrence SET title=?,description=?,starts_at=?,ends_at=?,is_override=true,
                  assignee_user_id=CASE WHEN ? THEN assignee_user_id ELSE NULL END,
                  assignment_origin=CASE WHEN ? THEN assignment_origin ELSE NULL END,version=version+1
                WHERE id=? AND version=? AND status='PENDING'
                """,title,description,Timestamp.from(starts),Timestamp.from(ends),keepAssignee,keepAssignee,id,version);
    }
    public int applySeriesRevision(UUID id,long version,int revisionNo,String title,String description,
            Instant starts,Instant ends,boolean keepAssignee) {
        return jdbc.update("""
                UPDATE task_occurrence SET revision_no=?,title=?,description=?,starts_at=?,ends_at=?,is_override=false,
                  assignee_user_id=CASE WHEN ? THEN assignee_user_id ELSE NULL END,
                  assignment_origin=CASE WHEN ? THEN assignment_origin ELSE NULL END,version=version+1
                WHERE id=? AND version=? AND status='PENDING'
                """,revisionNo,title,description,Timestamp.from(starts),Timestamp.from(ends),keepAssignee,keepAssignee,id,version);
    }
    public int cancelOccurrence(UUID id,long version,String reason,Instant now) {
        return jdbc.update("""
                UPDATE task_occurrence SET status='CANCELED',cancel_reason=?,canceled_at=?,version=version+1
                WHERE id=? AND version=? AND status='PENDING'
                """,reason,Timestamp.from(now),id,version);
    }
    public int reviveRuleChanged(UUID id,long version,int revisionNo,String title,String description,
            Instant starts,Instant ends) {
        return jdbc.update("""
                UPDATE task_occurrence SET revision_no=?,title=?,description=?,starts_at=?,ends_at=?,status='PENDING',
                  cancel_reason=NULL,canceled_at=NULL,is_override=false,assignee_user_id=NULL,assignment_origin=NULL,
                  version=version+1 WHERE id=? AND version=? AND status='CANCELED' AND cancel_reason='RULE_CHANGED'
                """,revisionNo,title,description,Timestamp.from(starts),Timestamp.from(ends),id,version);
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
                       h.closed_at,h.close_reason,h.created_at,h.version
                FROM handoff_request h LEFT JOIN app_user prev ON prev.id=h.previous_assignee_id
                LEFT JOIN app_user req ON req.id=h.requested_by LEFT JOIN app_user acc ON acc.id=h.accepted_by
                WHERE h.occurrence_id=? AND h.status='OPEN'
                """,this::mapHandoff,occurrenceId).stream().findFirst();
    }
    public Optional<Handoff> findHandoff(UUID id) {
        return jdbc.query("""
                SELECT h.id,h.occurrence_id,h.reason,h.previous_assignee_id,prev.display_name,
                       h.requested_by,req.display_name,h.status,h.accepted_by,acc.display_name,
                       h.closed_at,h.close_reason,h.created_at,h.version
                FROM handoff_request h LEFT JOIN app_user prev ON prev.id=h.previous_assignee_id
                LEFT JOIN app_user req ON req.id=h.requested_by LEFT JOIN app_user acc ON acc.id=h.accepted_by
                WHERE h.id=?
                """,this::mapHandoff,id).stream().findFirst();
    }
    public Optional<Handoff> findLatestHandoff(UUID occurrenceId){return jdbc.query("""
            SELECT h.id,h.occurrence_id,h.reason,h.previous_assignee_id,prev.display_name,
                   h.requested_by,req.display_name,h.status,h.accepted_by,acc.display_name,
                   h.closed_at,h.close_reason,h.created_at,h.version
            FROM handoff_request h LEFT JOIN app_user prev ON prev.id=h.previous_assignee_id
            LEFT JOIN app_user req ON req.id=h.requested_by LEFT JOIN app_user acc ON acc.id=h.accepted_by
            WHERE h.occurrence_id=? ORDER BY h.created_at DESC,h.id DESC LIMIT 1
            """,this::mapHandoff,occurrenceId).stream().findFirst();}
    public boolean hasDeclined(UUID handoffId,UUID userId){return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM handoff_response WHERE handoff_id=? AND user_id=?)",Boolean.class,handoffId,userId));}
    public Instant declineHandoff(UUID groupId,UUID handoffId,UUID userId,Instant now){
        jdbc.update("INSERT INTO handoff_response(group_id,handoff_id,user_id,response,responded_at) VALUES (?,?,?,'DECLINED',?) ON CONFLICT (handoff_id,user_id) DO NOTHING",
                groupId,handoffId,userId,Timestamp.from(now));
        return jdbc.queryForObject("SELECT responded_at FROM handoff_response WHERE handoff_id=? AND user_id=?",
                (r,n)->r.getTimestamp(1).toInstant(),handoffId,userId);
    }
    public UUID openHandoff(UUID groupId,UUID occurrenceId,String reason,UUID previous,UUID requestedBy) {
        UUID id=UUID.randomUUID();
        int changed=jdbc.update("""
                INSERT INTO handoff_request(id,group_id,occurrence_id,reason,previous_assignee_id,requested_by,status)
                SELECT ?,?,?,?,?,?,'OPEN' WHERE NOT EXISTS
                  (SELECT 1 FROM handoff_request WHERE occurrence_id=? AND status='OPEN')
                """,id,groupId,occurrenceId,reason,previous,requestedBy,occurrenceId);
        return changed==1?id:null;
    }
    public int releaseAssignee(UUID id,long version) {
        return jdbc.update("""
                UPDATE task_occurrence SET assignee_user_id=NULL,assignment_origin=NULL,version=version+1
                WHERE id=? AND version=? AND status='PENDING' AND assignee_user_id IS NOT NULL
                """,id,version);
    }
    public int acceptHandoff(UUID handoffId,long handoffVersion,UUID occurrenceId,long occurrenceVersion,
            UUID userId,Instant now) {
        int task=jdbc.update("""
                UPDATE task_occurrence SET assignee_user_id=?,assignment_origin='HANDOFF',version=version+1
                WHERE id=? AND version=? AND status='PENDING' AND assignee_user_id IS NULL
                """,userId,occurrenceId,occurrenceVersion);
        if(task!=1)return 0;
        int handoff=jdbc.update("""
                UPDATE handoff_request SET status='ACCEPTED',accepted_by=?,closed_at=?,version=version+1
                WHERE id=? AND version=? AND status='OPEN'
                """,userId,Timestamp.from(now),handoffId,handoffVersion);
        if(handoff!=1)throw new IllegalStateException("Concurrent handoff acceptance");
        return 1;
    }
    public int expireOpenHandoff(UUID handoffId,long version,Instant now) {
        return jdbc.update("""
                UPDATE handoff_request SET status='EXPIRED',closed_at=?,close_reason='TASK_OVERDUE',version=version+1
                WHERE id=? AND version=? AND status='OPEN'
                """,Timestamp.from(now),handoffId,version);
    }
    public List<Handoff> listHandoffs(UUID groupId,String status,int fetch,CursorService.Value cursor) {
        String sql="""
                SELECT h.id,h.occurrence_id,h.reason,h.previous_assignee_id,prev.display_name,
                       h.requested_by,req.display_name,h.status,h.accepted_by,acc.display_name,
                       h.closed_at,h.close_reason,h.created_at,h.version
                FROM handoff_request h LEFT JOIN app_user prev ON prev.id=h.previous_assignee_id
                LEFT JOIN app_user req ON req.id=h.requested_by LEFT JOIN app_user acc ON acc.id=h.accepted_by
                WHERE h.group_id=? AND h.status=?
                """;
        List<Object> args=new ArrayList<>(List.of(groupId,status));
        if(cursor!=null){sql+=" AND (h.created_at,h.id)<(?,?)";args.add(Timestamp.from(cursor.time()));args.add(cursor.id());}
        sql+=" ORDER BY h.created_at DESC,h.id DESC LIMIT ?";args.add(fetch);
        return jdbc.query(sql,this::mapHandoff,args.toArray());
    }
    public List<Handoff> findOverdueOpenHandoffs(UUID groupId,Instant now) {
        return jdbc.query("""
                SELECT h.id,h.occurrence_id,h.reason,h.previous_assignee_id,prev.display_name,
                       h.requested_by,req.display_name,h.status,h.accepted_by,acc.display_name,
                       h.closed_at,h.close_reason,h.created_at,h.version
                FROM handoff_request h JOIN task_occurrence o ON o.id=h.occurrence_id
                LEFT JOIN app_user prev ON prev.id=h.previous_assignee_id
                LEFT JOIN app_user req ON req.id=h.requested_by LEFT JOIN app_user acc ON acc.id=h.accepted_by
                WHERE h.group_id=? AND h.status='OPEN' AND o.status='PENDING' AND o.ends_at<=?
                ORDER BY h.created_at,h.id
                """,this::mapHandoff,groupId,Timestamp.from(now));
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
            UPDATE notification_event SET status='CANCELED',lease_token=NULL,lease_until=NULL
            WHERE occurrence_id=? AND status IN ('PENDING','FAILED','RUNNING')
            """,occurrenceId); }
    public void cancelPendingDeliveries(UUID occurrenceId) { jdbc.update("""
            UPDATE notification_delivery d SET status='CANCELED',lease_token=NULL,lease_until=NULL
            FROM notification n JOIN notification_event e ON e.id=n.event_id
            WHERE d.notification_id=n.id AND e.occurrence_id=? AND d.status IN ('PENDING','FAILED','RUNNING')
            """,occurrenceId); }
    public void cancelPendingNotificationsForUser(UUID occurrenceId,UUID userId){
        jdbc.update("UPDATE notification_event SET status='CANCELED',lease_token=NULL,lease_until=NULL WHERE occurrence_id=? AND target_user_id=? AND status IN ('PENDING','FAILED','RUNNING')",occurrenceId,userId);
        jdbc.update("""
                UPDATE notification_delivery d SET status='CANCELED',lease_token=NULL,lease_until=NULL
                FROM notification n JOIN notification_event e ON e.id=n.event_id
                WHERE d.notification_id=n.id AND e.occurrence_id=? AND n.user_id=? AND d.status IN ('PENDING','FAILED','RUNNING')
                """,occurrenceId,userId);
    }
    public List<Occurrence> futureAssigned(UUID groupId,UUID userId,Instant now) {
        return jdbc.query(OCCURRENCE_SELECT+" WHERE o.group_id=? AND o.assignee_user_id=? AND o.status='PENDING' AND o.starts_at>=? ORDER BY o.starts_at,o.id",
                this::mapOccurrence,groupId,userId,Timestamp.from(now));
    }
    public List<Occurrence> homeToday(UUID groupId,UUID userId,Instant from,Instant to,int fetch) {
        return jdbc.query(OCCURRENCE_SELECT+" WHERE o.group_id=? AND o.assignee_user_id=? AND o.status='PENDING' AND o.starts_at>=? AND o.starts_at<? ORDER BY o.starts_at,o.id LIMIT ?",
                this::mapOccurrence,groupId,userId,Timestamp.from(from),Timestamp.from(to),fetch);
    }
    public List<Occurrence> homeUnassigned(UUID groupId,Instant now,int fetch) {
        return jdbc.query(OCCURRENCE_SELECT+" WHERE o.group_id=? AND o.status='PENDING' AND o.assignee_user_id IS NULL AND o.starts_at>=? ORDER BY o.starts_at,o.id LIMIT ?",
                this::mapOccurrence,groupId,Timestamp.from(now),fetch);
    }
    public List<Occurrence> homeOverdue(UUID groupId,Instant now,int fetch) {
        return jdbc.query(OCCURRENCE_SELECT+" WHERE o.group_id=? AND o.status='PENDING' AND o.ends_at<=? ORDER BY o.starts_at,o.id LIMIT ?",
                this::mapOccurrence,groupId,Timestamp.from(now),fetch);
    }
    public int reviewEncounterCount(UUID groupId) {
        return jdbc.queryForObject("""
                SELECT count(DISTINCT e.id) FROM encounter e
                JOIN encounter_revision r ON r.encounter_id=e.id AND r.is_current
                JOIN extracted_item x ON x.revision_id=r.id
                WHERE e.group_id=? AND e.deleted_at IS NULL AND x.review_state='NEEDS_REVIEW'
                """,Integer.class,groupId);
    }
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
            r.getObject("id",UUID.class),r.getObject("group_id",UUID.class),r.getObject("created_by",UUID.class),r.getString("creation_origin"),r.getString("kind"),r.getString("title"),r.getString("description"),
            r.getString("recurrence"),r.getObject("first_date",LocalDate.class),r.getObject("last_date",LocalDate.class),weekdays,
            r.getObject("local_time",LocalTime.class),r.getInt("duration_minutes"),r.getObject("stop_from_date",LocalDate.class),
            r.getInt("current_revision_no"),r.getLong("version"));}
    private Handoff mapHandoff(ResultSet r,int n)throws SQLException{return new Handoff(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),
            r.getObject(4,UUID.class),r.getString(5),r.getObject(6,UUID.class),r.getString(7),r.getString(8),r.getObject(9,UUID.class),
            r.getString(10),instant(r,"closed_at"),r.getString(12),r.getTimestamp(13).toInstant(),r.getLong(14));}
    private Audit mapAudit(ResultSet r,int n)throws SQLException{return new Audit(r.getObject(1,UUID.class),r.getString(2),r.getObject(3,UUID.class),
            r.getString(4),r.getString(5),r.getString(6),r.getTimestamp(7).toInstant());}
    private TaskDtos.Medication mapMedication(ResultSet r,int n)throws SQLException{return new TaskDtos.Medication(
            r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4),r.getObject(5,LocalDate.class),
            r.getObject(6,LocalDate.class),r.getString(7),new TaskDtos.UserRef(r.getObject(8,UUID.class),r.getString(9)),
            r.getTimestamp(10).toInstant().atZone(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime(),r.getObject(11,UUID.class));}
    private Instant instant(ResultSet r,String name)throws SQLException{Timestamp value=r.getTimestamp(name);return value==null?null:value.toInstant();}
    public record RankedOccurrence(Occurrence occurrence,int rank){}
    public record CalendarCount(LocalDate date,long total,long completed,long unassigned){}
}
