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
import com.kw.knowone.common.web.CursorService;

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
                SELECT g.id, g.recipient_user_id, u.display_name, g.name, g.status, g.version,
                       g.parent_relation,g.parent_birth_year,g.parent_profile_completed_at
                FROM care_group g JOIN app_user u ON u.id = g.recipient_user_id
                WHERE g.id = ?
                """, this::mapGroup, groupId).stream().findFirst();
    }

    public List<CareGroup> findGroupsForActiveMember(UUID userId) {
        return jdbcTemplate.query("""
                SELECT g.id, g.recipient_user_id, recipient.display_name, g.name, g.status, g.version,
                       g.parent_relation,g.parent_birth_year,g.parent_profile_completed_at
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
                SELECT g.id, g.recipient_user_id, u.display_name, g.name, g.status, g.version,
                       g.parent_relation,g.parent_birth_year,g.parent_profile_completed_at
                FROM care_group g JOIN app_user u ON u.id = g.recipient_user_id
                WHERE u.phone_number = ? AND u.account_role = 'PARENT' AND u.status = 'ACTIVE'
                """, this::mapGroup, phoneNumber).stream().findFirst();
    }

    public GroupMember insertCaregiver(UUID id, UUID groupId, UUID userId, int priority) {
        jdbcTemplate.update("""
                INSERT INTO group_member(id, group_id, user_id, role, priority)
                VALUES (?, ?, ?, 'CAREGIVER', ?)
                """, id, groupId, userId, priority);
        return findMembership(groupId, userId).orElseThrow();
    }

    public GroupMember reopenCaregiver(GroupMember member,int priority,String status,Instant now){
        int changed=jdbcTemplate.update("""
                UPDATE group_member SET priority=?,status=?,joined_at=?,left_at=NULL,version=version+1
                WHERE id=? AND version=? AND role='CAREGIVER' AND status='LEFT'
                """,priority,status,"ACTIVE".equals(status)?Timestamp.from(now):null,member.id(),member.version());
        if(changed!=1)throw new IllegalStateException("Concurrent membership reopen");
        return findMembership(member.groupId(),member.userId()).orElseThrow();
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

    public GroupMember insertPendingCaregiver(UUID id, UUID groupId, UUID userId) {
        jdbcTemplate.update("""
                INSERT INTO group_member(id,group_id,user_id,role,priority,status,joined_at)
                VALUES (?,?,?,'CAREGIVER',2,'PENDING',NULL)
                """, id, groupId, userId);
        return findMembership(groupId,userId).orElseThrow();
    }

    public int activeCaregiverCount(UUID groupId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM group_member WHERE group_id=? AND role='CAREGIVER' AND status='ACTIVE'", Integer.class, groupId);
    }

    public boolean hasCurrentMembership(UUID userId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT EXISTS(SELECT 1 FROM group_member WHERE user_id=? AND status IN ('ACTIVE','PENDING'))", Boolean.class, userId));
    }

    public void completeParentProfile(CareGroup group, UUID childUserId, String relation, String name, int birthYear, Instant now) {
        jdbcTemplate.update("UPDATE app_user SET display_name=?,updated_at=?,version=version+1 WHERE id=?",
                name,Timestamp.from(now),group.recipientUserId());
        int changed=jdbcTemplate.update("""
                UPDATE care_group SET parent_relation=?,parent_birth_year=?,parent_profile_completed_at=?,
                  parent_profile_completed_by=?,updated_at=?,version=version+1
                WHERE id=? AND version=? AND parent_profile_completed_at IS NULL
                """,relation,birthYear,Timestamp.from(now),childUserId,Timestamp.from(now),group.id(),group.version());
        if(changed!=1) throw new IllegalStateException("Concurrent parent profile update");
    }

    public JoinRow insertJoinRequest(UUID id, UUID groupId, UUID userId, String status, String decisionKind,
            UUID decidedBy, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO group_join_request(id,group_id,user_id,status,decision_kind,decided_by,decided_at,created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """,id,groupId,userId,status,decisionKind,decidedBy,
                decidedBy==null?null:Timestamp.from(now),Timestamp.from(now));
        return findJoinRequest(id).orElseThrow();
    }

    public Optional<JoinRow> findJoinRequest(UUID id) {
        return jdbcTemplate.query("""
                SELECT r.id,r.group_id,r.user_id,r.status,r.version,r.created_at,r.decided_at,m.status membership_status,m.priority
                FROM group_join_request r JOIN group_member m ON m.group_id=r.group_id AND m.user_id=r.user_id
                WHERE r.id=?
                """,this::mapJoin,id).stream().findFirst();
    }

    public Optional<JoinRow> findLatestJoinForUser(UUID userId) {
        return jdbcTemplate.query("""
                SELECT r.id,r.group_id,r.user_id,r.status,r.version,r.created_at,r.decided_at,m.status membership_status,m.priority
                FROM group_join_request r JOIN group_member m ON m.group_id=r.group_id AND m.user_id=r.user_id
                WHERE r.user_id=? ORDER BY r.created_at DESC,r.id DESC LIMIT 1
                """,this::mapJoin,userId).stream().findFirst();
    }

    public List<JoinRow> findPendingJoins(UUID groupId,CursorService.Value cursor,int fetch) {
        String sql="""
                SELECT r.id,r.group_id,r.user_id,r.status,r.version,r.created_at,r.decided_at,m.status membership_status,m.priority
                FROM group_join_request r JOIN group_member m ON m.group_id=r.group_id AND m.user_id=r.user_id
                WHERE r.group_id=? AND r.status='PENDING'
                """;
        List<Object> args=new java.util.ArrayList<>();args.add(groupId);
        if(cursor!=null){sql+=" AND (r.created_at,r.id)>(?,?)";args.add(Timestamp.from(cursor.time()));args.add(cursor.id());}
        sql+=" ORDER BY r.created_at,r.id LIMIT ?";args.add(fetch);
        return jdbcTemplate.query(sql,this::mapJoin,args.toArray());
    }

    public JoinRow decideJoin(JoinRow row, UUID actor, String decision, Instant now) {
        String status="APPROVE".equals(decision)?"APPROVED":"REJECTED";
        int changed=jdbcTemplate.update("""
                UPDATE group_join_request SET status=?,decision_kind='REVIEW',decided_by=?,decided_at=?,version=version+1
                WHERE id=? AND version=? AND status='PENDING'
                """,status,actor,Timestamp.from(now),row.id(),row.version());
        if(changed!=1) return null;
        jdbcTemplate.update("""
                UPDATE group_member SET status=?,joined_at=?,left_at=?,version=version+1
                WHERE group_id=? AND user_id=? AND status='PENDING'
                ""","APPROVE".equals(decision)?"ACTIVE":"LEFT",
                "APPROVE".equals(decision)?Timestamp.from(now):null,
                "APPROVE".equals(decision)?null:Timestamp.from(now),row.groupId(),row.userId());
        return findJoinRequest(row.id()).orElseThrow();
    }

    public JoinRow cancelJoin(JoinRow row, Instant now) {
        int changed=jdbcTemplate.update("""
                UPDATE group_join_request SET status='CANCELED',decision_kind='REVIEW',decided_by=?,decided_at=?,version=version+1
                WHERE id=? AND version=? AND status='PENDING'
                """,row.userId(),Timestamp.from(now),row.id(),row.version());
        if(changed!=1)return null;
        jdbcTemplate.update("UPDATE group_member SET status='LEFT',left_at=?,version=version+1 WHERE group_id=? AND user_id=? AND status='PENDING'",
                Timestamp.from(now),row.groupId(),row.userId());
        return findJoinRequest(row.id()).orElseThrow();
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
                rs.getString(4), rs.getString(5), rs.getLong(6),rs.getString(7),rs.getObject(8,Integer.class),
                rs.getTimestamp(9)==null?null:rs.getTimestamp(9).toInstant());
    }

    private GroupMember mapMember(ResultSet rs, int rowNum) throws SQLException {
        return new GroupMember(rs.getObject("id", UUID.class), rs.getObject("group_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("display_name"), rs.getString("role"),
                rs.getObject("priority", Integer.class), rs.getString("status"), rs.getLong("version"));
    }

    private JoinRow mapJoin(ResultSet rs,int n)throws SQLException{
        return new JoinRow(rs.getObject("id",UUID.class),rs.getObject("group_id",UUID.class),
                rs.getObject("user_id",UUID.class),rs.getString("status"),rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("decided_at")==null?null:rs.getTimestamp("decided_at").toInstant(),
                rs.getString("membership_status"),rs.getObject("priority",Integer.class));
    }

    public record JoinRow(UUID id,UUID groupId,UUID userId,String status,long version,Instant createdAt,
            Instant decidedAt,String membershipStatus,Integer priority){}
}
