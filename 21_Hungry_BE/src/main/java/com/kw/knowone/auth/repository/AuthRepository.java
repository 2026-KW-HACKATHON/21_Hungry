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
                SELECT id, login_key, display_name, phone_number, account_role, account_type, status, timezone,
                       active_start_minute, active_end_minute, version
                FROM app_user
                WHERE account_type = 'DEMO' AND status = 'ACTIVE'
                ORDER BY created_at, id
                """, this::mapUser);
    }

    public Optional<AppUser> findActiveDemoByLoginKey(String loginKey) {
        return jdbcTemplate.query("""
                SELECT id, login_key, display_name, phone_number, account_role, account_type, status, timezone,
                       active_start_minute, active_end_minute, version
                FROM app_user
                WHERE login_key = ? AND account_type = 'DEMO' AND status = 'ACTIVE'
                """, this::mapUser, loginKey).stream().findFirst();
    }

    public Optional<AppUser> findActiveById(UUID userId) {
        return jdbcTemplate.query("""
                SELECT id, login_key, display_name, phone_number, account_role, account_type, status, timezone,
                       active_start_minute, active_end_minute, version
                FROM app_user WHERE id = ? AND status = 'ACTIVE'
                """, this::mapUser, userId).stream().findFirst();
    }

    public Optional<AppUser> findActiveByPhoneNumber(String phoneNumber) {
        return jdbcTemplate.query("""
                SELECT id, login_key, display_name, phone_number, account_role, account_type, status, timezone,
                       active_start_minute, active_end_minute, version
                FROM app_user WHERE phone_number = ? AND status = 'ACTIVE'
                """, this::mapUser, phoneNumber).stream().findFirst();
    }

    public int insertUser(UUID id, String phoneNumber, String accountRole, String displayName, Instant now) {
        return jdbcTemplate.update("""
                INSERT INTO app_user(id, phone_number, account_role, display_name, account_type, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'REAL', ?, ?)
                ON CONFLICT (phone_number) DO NOTHING
                """, id, phoneNumber, accountRole, displayName, Timestamp.from(now), Timestamp.from(now));
    }

    public UUID createParentGroup(UUID userId, Instant now) {
        UUID groupId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO care_group(id, recipient_user_id, name, created_at, updated_at)
                VALUES (?, ?, '가족 돌봄 공동체', ?, ?)
                """, groupId, userId, Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("""
                INSERT INTO group_member(id, group_id, user_id, role, priority, status, joined_at, updated_at)
                VALUES (?, ?, ?, 'RECIPIENT', NULL, 'ACTIVE', ?, ?)
                """, UUID.randomUUID(), groupId, userId, Timestamp.from(now), Timestamp.from(now));
        return groupId;
    }

    public Optional<MembershipSummary> findCurrentMembership(UUID userId) {
        return jdbcTemplate.query("""
                SELECT m.group_id,m.status,m.priority,
                       (SELECT r.id FROM group_join_request r
                        WHERE r.group_id=m.group_id AND r.user_id=m.user_id
                        ORDER BY r.created_at DESC,r.id DESC LIMIT 1) join_request_id,
                       g.parent_profile_completed_at
                FROM group_member m JOIN care_group g ON g.id=m.group_id
                WHERE m.user_id=? AND m.status IN ('ACTIVE','PENDING')
                """, (rs,n) -> new MembershipSummary(rs.getObject(1,UUID.class), rs.getString(2),
                        rs.getObject(3,Integer.class), rs.getObject(4,UUID.class), rs.getTimestamp(5)!=null), userId)
                .stream().findFirst();
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
                rs.getString("phone_number"), rs.getString("account_role"), rs.getString("account_type"), rs.getString("status"),
                rs.getString("timezone"), rs.getInt("active_start_minute"), rs.getInt("active_end_minute"),
                rs.getLong("version"));
    }

    public record MembershipSummary(UUID groupId, String status, Integer priority, UUID joinRequestId,
            boolean parentProfileCompleted) { }
}
