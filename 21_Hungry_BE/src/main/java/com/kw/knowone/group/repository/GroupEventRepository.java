package com.kw.knowone.group.repository;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class GroupEventRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public GroupEventRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void audit(UUID groupId, UUID actorUserId, String eventType, String entityType, UUID entityId,
            Object before, Object after, UUID requestId) {
        jdbcTemplate.update("""
                INSERT INTO audit_event
                  (id, group_id, actor_user_id, event_type, entity_type, entity_id, before_data, after_data, request_id)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)
                """, UUID.randomUUID(), groupId, actorUserId, eventType, entityType, entityId,
                before == null ? null : json(before), after == null ? null : json(after), requestId);
    }

    public void notification(UUID groupId, String eventType, String eventKey, Object payload, Instant dueAt) {
        jdbcTemplate.update("""
                INSERT INTO notification_event(id, group_id, event_type, event_key, due_at, payload)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, UUID.randomUUID(), groupId, eventType, eventKey, Timestamp.from(dueAt), json(payload));
    }

    public void taskNotification(UUID groupId, String eventType, String eventKey, UUID occurrenceId,
            UUID handoffId, UUID targetUserId, Long expectedTaskVersion, Object payload, Instant dueAt) {
        jdbcTemplate.update("""
                INSERT INTO notification_event
                  (id, group_id, event_type, event_key, occurrence_id, handoff_id, target_user_id,
                   expected_task_version, due_at, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, UUID.randomUUID(), groupId, eventType, eventKey, occurrenceId, handoffId, targetUserId,
                expectedTaskVersion, Timestamp.from(dueAt), json(payload));
    }

    public void syncOccurrenceNotifications(UUID occurrenceId, Instant now) {
        jdbcTemplate.update("""
            UPDATE notification_delivery d SET status='CANCELED',lease_token=NULL,lease_until=NULL
            FROM notification n JOIN notification_event e ON e.id=n.event_id
            JOIN task_occurrence o ON o.id=e.occurrence_id
            WHERE d.notification_id=n.id AND e.occurrence_id=? AND e.event_type IN ('DUE_30M','DUE_NOW','OVERDUE')
              AND e.expected_task_version<>o.version AND d.status IN ('PENDING','FAILED','RUNNING')
            """, occurrenceId);
        jdbcTemplate.update("""
            UPDATE notification_event e SET status='CANCELED',lease_token=NULL,lease_until=NULL
            FROM task_occurrence o WHERE e.occurrence_id=o.id AND o.id=?
              AND e.event_type IN ('DUE_30M','DUE_NOW','OVERDUE') AND e.expected_task_version<>o.version
              AND e.status IN ('PENDING','FAILED','RUNNING','EXPANDED')
            """, occurrenceId);
        jdbcTemplate.update("""
            INSERT INTO notification_event(id,group_id,event_type,event_key,occurrence_id,expected_task_version,due_at,payload)
            SELECT gen_random_uuid(),o.group_id,v.type,'task:'||o.id||':v'||o.version||':'||v.suffix,
                   o.id,o.version,v.due_at,jsonb_build_object('schemaVersion',1)
            FROM task_occurrence o CROSS JOIN LATERAL (VALUES
              ('DUE_30M','30m',o.starts_at-interval '30 minutes'),
              ('DUE_NOW','now',o.starts_at),('OVERDUE','overdue',o.ends_at)
            ) v(type,suffix,due_at)
            WHERE o.id=? AND o.status='PENDING' AND (v.type='OVERDUE' OR o.assignee_user_id IS NOT NULL) AND v.due_at>?
            ON CONFLICT (event_key) DO NOTHING
            """, occurrenceId, Timestamp.from(now));
    }

    public void syncAllUpcomingNotifications(Instant now) {
        for(UUID id:jdbcTemplate.query("""
            SELECT id FROM task_occurrence WHERE status='PENDING' AND ends_at>?
            """,(rs,n)->rs.getObject(1,UUID.class),Timestamp.from(now))) syncOccurrenceNotifications(id,now);
    }

    public void createDailyDigests(Instant now) {
        jdbcTemplate.update("""
          INSERT INTO notification_event(id,group_id,event_type,event_key,target_user_id,due_at,payload)
          SELECT gen_random_uuid(),m.group_id,'DAILY_DIGEST',
            'digest:'||m.group_id||':'||m.user_id||':'||to_char(? AT TIME ZONE 'Asia/Seoul','YYYY-MM-DD'),
            m.user_id,?,jsonb_build_object('schemaVersion',1)
          FROM group_member m JOIN care_group g ON g.id=m.group_id AND g.status='ACTIVE'
          WHERE m.status='ACTIVE'
            AND EXISTS (
              SELECT 1 FROM handoff_request h JOIN task_occurrence o ON o.id=h.occurrence_id
              WHERE h.group_id=m.group_id AND h.status='OPEN' AND o.status='PENDING' AND o.assignee_user_id IS NULL
                AND NOT EXISTS (SELECT 1 FROM handoff_response r WHERE r.handoff_id=h.id AND r.user_id=m.user_id))
          ON CONFLICT (event_key) DO NOTHING
          """,Timestamp.from(now),Timestamp.from(now));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Event serialization failed", exception);
        }
    }
}
