package com.kw.knowone.group.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.kw.knowone.group.entity.CareGroup;
import com.kw.knowone.group.entity.GroupMember;

@Repository
public class GroupRepository {
    private static final String MEMBER_SELECT = """
            SELECT m.id, m.group_id, m.user_id, u.display_name, m.role, m.priority, m.status, m.version
            FROM group_member m JOIN app_user u ON u.id = m.user_id
            """;
    private final JdbcTemplate jdbcTemplate;

    public GroupRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<CareGroup> findGroup(UUID groupId) {
        return jdbcTemplate.query("""
                SELECT g.id, g.recipient_user_id, u.display_name, g.name, g.status, g.version
                FROM care_group g JOIN app_user u ON u.id = g.recipient_user_id
                WHERE g.id = ?
                """, this::mapGroup, groupId).stream().findFirst();
    }

    public List<CareGroup> findGroupsForActiveMember(UUID userId) {
        return jdbcTemplate.query("""
                SELECT g.id, g.recipient_user_id, recipient.display_name, g.name, g.status, g.version
                FROM care_group g
                JOIN app_user recipient ON recipient.id = g.recipient_user_id
                JOIN group_member mine ON mine.group_id = g.id
                WHERE mine.user_id = ? AND mine.status = 'ACTIVE'
                ORDER BY g.created_at, g.id
                """, this::mapGroup, userId);
    }

    public Optional<GroupMember> findMembership(UUID groupId, UUID userId) {
        return jdbcTemplate.query(MEMBER_SELECT + " WHERE m.group_id = ? AND m.user_id = ?",
                this::mapMember, groupId, userId).stream().findFirst();
    }

    public Optional<GroupMember> findActiveMembership(UUID groupId, UUID userId) {
        return jdbcTemplate.query(MEMBER_SELECT + " WHERE m.group_id = ? AND m.user_id = ? AND m.status = 'ACTIVE'",
                this::mapMember, groupId, userId).stream().findFirst();
    }

    public Optional<GroupMember> findMemberById(UUID memberId) {
        return jdbcTemplate.query(MEMBER_SELECT + " WHERE m.id = ?", this::mapMember, memberId).stream().findFirst();
    }

    public List<GroupMember> findActiveMembers(UUID groupId) {
        return jdbcTemplate.query(MEMBER_SELECT + """
                 WHERE m.group_id = ? AND m.status = 'ACTIVE'
                 ORDER BY m.priority ASC NULLS LAST, m.id ASC
                """, this::mapMember, groupId);
    }

    public Optional<CareGroup> findDemoRecipientByPhone(String phoneNumber) {
        return jdbcTemplate.query("""
                SELECT g.id, g.recipient_user_id, u.display_name, g.name, g.status, g.version
                FROM care_group g JOIN app_user u ON u.id = g.recipient_user_id
                WHERE u.phone_number = ? AND u.account_type = 'DEMO' AND u.status = 'ACTIVE'
                """, this::mapGroup, phoneNumber).stream().findFirst();
    }

    public GroupMember insertCaregiver(UUID id, UUID groupId, UUID userId, int priority) {
        jdbcTemplate.update("""
                INSERT INTO group_member(id, group_id, user_id, role, priority)
                VALUES (?, ?, ?, 'CAREGIVER', ?)
                """, id, groupId, userId, priority);
        return findMembership(groupId, userId).orElseThrow();
    }

    public GroupMember reactivate(GroupMember member, Instant now) {
        int changed = jdbcTemplate.update("""
                UPDATE group_member
                SET status = 'ACTIVE', left_at = NULL, joined_at = ?, version = version + 1
                WHERE id = ? AND version = ? AND status = 'LEFT'
                """, Timestamp.from(now), member.id(), member.version());
        if (changed != 1) throw new IllegalStateException("Concurrent membership update");
        return findMembership(member.groupId(), member.userId()).orElseThrow();
    }

    public GroupMember updatePriority(GroupMember member, int priority) {
        int changed = jdbcTemplate.update("""
                UPDATE group_member SET priority = ?, version = version + 1
                WHERE id = ? AND group_id = ? AND version = ? AND status = 'ACTIVE' AND role = 'CAREGIVER'
                """, priority, member.id(), member.groupId(), member.version());
        if (changed != 1) throw new IllegalStateException("Concurrent priority update");
        return findMemberById(member.id()).orElseThrow();
    }

    public GroupMember leave(GroupMember member, Instant now) {
        int changed=jdbcTemplate.update("""
                UPDATE group_member SET status='LEFT',left_at=?,version=version+1
                WHERE id=? AND version=? AND status='ACTIVE' AND role='CAREGIVER'
                """,Timestamp.from(now),member.id(),member.version());
        if(changed!=1)throw new IllegalStateException("Concurrent membership leave");
        return findMembership(member.groupId(),member.userId()).orElseThrow();
    }

    public void cancelUndeliveredNotifications(UUID groupId,UUID userId) {
        jdbcTemplate.update("""
                UPDATE notification_delivery d SET status='CANCELED',lease_token=NULL,lease_until=NULL
                FROM notification n WHERE d.notification_id=n.id AND n.group_id=? AND n.user_id=?
                  AND d.status IN ('PENDING','FAILED','RUNNING')
                """,groupId,userId);
        jdbcTemplate.update("""
                UPDATE notification_event SET status='CANCELED',lease_token=NULL,lease_until=NULL
                WHERE group_id=? AND target_user_id=? AND status IN ('PENDING','FAILED','RUNNING')
                """,groupId,userId);
    }

    private CareGroup mapGroup(ResultSet rs, int rowNum) throws SQLException {
        return new CareGroup(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getLong(6));
    }

    private GroupMember mapMember(ResultSet rs, int rowNum) throws SQLException {
        return new GroupMember(rs.getObject("id", UUID.class), rs.getObject("group_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("display_name"), rs.getString("role"),
                rs.getObject("priority", Integer.class), rs.getString("status"), rs.getLong("version"));
    }
}
