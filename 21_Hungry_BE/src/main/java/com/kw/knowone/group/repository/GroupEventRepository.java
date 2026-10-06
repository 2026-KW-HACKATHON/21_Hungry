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

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Event serialization failed", exception);
        }
    }
}
