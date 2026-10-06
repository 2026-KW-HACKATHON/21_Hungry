package com.kw.knowone.common.idempotency;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kw.knowone.auth.service.AuthService;
import com.kw.knowone.common.web.ApiException;
import tools.jackson.databind.ObjectMapper;

@Service
public class IdempotencyService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration ttl;

    public IdempotencyService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, Clock clock,
            @Value("${app.idempotency.ttl:PT24H}") Duration ttl) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.ttl = ttl;
    }

    @Transactional
    public IdempotentResult executeGuarded(UUID userId, String operation, String clientKey, Object request,
            Runnable authorizationCheck, Supplier<MutationResponse> mutation) {
        byte[] requestHash = hashRequest(request);
        jdbcTemplate.queryForList("SELECT id FROM schedule_guard WHERE id = 1 FOR UPDATE");
        authorizationCheck.run();
        return executeLocked(userId, operation, clientKey, requestHash, mutation);
    }

    public IdempotentResult executeLocked(UUID userId, String operation, String clientKey, Object request,
            Supplier<MutationResponse> mutation) {
        return executeLocked(userId, operation, clientKey, hashRequest(request), mutation);
    }

    private IdempotentResult executeLocked(UUID userId, String operation, String clientKey, byte[] requestHash,
            Supplier<MutationResponse> mutation) {
        validateKey(clientKey);
        long advisoryKey = ByteBuffer.wrap(AuthService.sha256(userId + ":" + operation + ":" + clientKey)).getLong();
        jdbcTemplate.queryForList("SELECT pg_advisory_xact_lock(?)", advisoryKey);

        Instant now = clock.instant();
        StoredResponse stored = jdbcTemplate.query("""
                SELECT request_hash, response_status, response_body::text, expires_at
                FROM idempotency_record
                WHERE user_id = ? AND operation = ? AND client_key = ?
                """, (rs, rowNum) -> new StoredResponse(rs.getBytes(1), rs.getInt(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant()), userId, operation, clientKey).stream().findFirst().orElse(null);
        if (stored != null && stored.expiresAt().isAfter(now)) {
            if (!Arrays.equals(stored.requestHash(), requestHash)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "같은 멱등 키가 다른 요청에 사용되었습니다.");
            }
            return new IdempotentResult(stored.status(), stored.body(), true);
        }
        if (stored != null) {
            jdbcTemplate.update("DELETE FROM idempotency_record WHERE user_id = ? AND operation = ? AND client_key = ?",
                    userId, operation, clientKey);
        }

        MutationResponse response = mutation.get();
        String body = writeJson(response.body());
        jdbcTemplate.update("""
                INSERT INTO idempotency_record
                  (id, user_id, operation, client_key, request_hash, response_status, response_body, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                """, UUID.randomUUID(), userId, operation, clientKey, requestHash, response.status(), body,
                Timestamp.from(now.plus(ttl)), Timestamp.from(now));
        String storedBody = jdbcTemplate.queryForObject("""
                SELECT response_body::text FROM idempotency_record
                WHERE user_id = ? AND operation = ? AND client_key = ?
                """, String.class, userId, operation, clientKey);
        return new IdempotentResult(response.status(), storedBody, false);
    }

    public void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key가 필요합니다.");
        }
        if (key.length() > 100 || key.chars().anyMatch(value -> value < 0x20 || value > 0x7e)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Idempotency-Key 형식이 올바르지 않습니다.",
                    Map.of("fieldErrors", java.util.List.of(Map.of(
                            "field", "Idempotency-Key", "code", "INVALID", "message", "1~100자 ASCII 문자열이어야 합니다."))));
        }
    }

    private byte[] hashRequest(Object request) {
        return AuthService.sha256(writeJson(request));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("JSON serialization failed", exception);
        }
    }

    private record StoredResponse(byte[] requestHash, int status, String body, Instant expiresAt) { }
}
