package com.kw.knowone.auth.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.kw.knowone.auth.entity.AppUser;
import com.kw.knowone.auth.entity.AuthSession;

@Repository
public class AuthRepository {
    private final JdbcTemplate jdbcTemplate;

    public AuthRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AppUser> findActiveDemoUsers() {
        return jdbcTemplate.query("""
                SELECT id, login_key, display_name, phone_number, account_type, status, timezone,
                       active_start_minute, active_end_minute, version
                FROM app_user
                WHERE account_type = 'DEMO' AND status = 'ACTIVE'
                ORDER BY created_at, id
                """, this::mapUser);
    }

    public Optional<AppUser> findActiveDemoByLoginKey(String loginKey) {
        return jdbcTemplate.query("""
                SELECT id, login_key, display_name, phone_number, account_type, status, timezone,
                       active_start_minute, active_end_minute, version
                FROM app_user
                WHERE login_key = ? AND account_type = 'DEMO' AND status = 'ACTIVE'
                """, this::mapUser, loginKey).stream().findFirst();
    }

    public Optional<AppUser> findActiveById(UUID userId) {
        return jdbcTemplate.query("""
                SELECT id, login_key, display_name, phone_number, account_type, status, timezone,
                       active_start_minute, active_end_minute, version
                FROM app_user WHERE id = ? AND status = 'ACTIVE'
                """, this::mapUser, userId).stream().findFirst();
    }

    public void createSession(UUID id, UUID userId, byte[] tokenHash, Instant expiresAt, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO auth_session(id, user_id, token_hash, expires_at, last_seen_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, userId, tokenHash, Timestamp.from(expiresAt), Timestamp.from(now), Timestamp.from(now));
    }

    public Optional<AuthSession> findSession(byte[] tokenHash) {
        return jdbcTemplate.query("""
                SELECT s.id, s.user_id, s.expires_at, s.revoked_at, u.status
                FROM auth_session s JOIN app_user u ON u.id = s.user_id
                WHERE s.token_hash = ?
                """, (rs, rowNum) -> new AuthSession(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant(),
                        rs.getString("status")), tokenHash).stream().findFirst();
    }

    public int revokeSession(UUID sessionId, UUID userId, Instant now) {
        return jdbcTemplate.update("""
                UPDATE auth_session SET revoked_at = ?
                WHERE id = ? AND user_id = ? AND revoked_at IS NULL
                """, Timestamp.from(now), sessionId, userId);
    }

    private AppUser mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new AppUser(
                rs.getObject("id", UUID.class), rs.getString("login_key"), rs.getString("display_name"),
                rs.getString("phone_number"), rs.getString("account_type"), rs.getString("status"),
                rs.getString("timezone"), rs.getInt("active_start_minute"), rs.getInt("active_end_minute"),
                rs.getLong("version"));
    }
}
