package com.kw.knowone.notification.repository;

import com.kw.knowone.common.web.CursorService;
import com.kw.knowone.notification.dto.NotificationDtos;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationRepository {
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    public NotificationRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public List<NotificationDtos.Item> list(UUID userId,UUID groupId,boolean unread,int fetch,CursorService.Value cursor){
        return jdbc.query("""
            SELECT n.id,n.group_id,n.event_id,e.event_type,n.title,n.body,n.read_at,n.created_at,
              CASE WHEN e.occurrence_id IS NOT NULL AND o.id IS NOT NULL THEN 'TASK'
                   WHEN e.encounter_id IS NOT NULL AND en.deleted_at IS NULL THEN 'ENCOUNTER' END target_type,
              CASE WHEN e.occurrence_id IS NOT NULL AND o.id IS NOT NULL THEN e.occurrence_id
                   WHEN e.encounter_id IS NOT NULL AND en.deleted_at IS NULL THEN e.encounter_id END target_id
            FROM notification n JOIN notification_event e ON e.id=n.event_id
            JOIN group_member gm ON gm.group_id=n.group_id AND gm.user_id=n.user_id AND gm.status='ACTIVE'
            JOIN care_group g ON g.id=n.group_id AND g.status='ACTIVE'
            LEFT JOIN task_occurrence o ON o.id=e.occurrence_id AND o.group_id=e.group_id
            LEFT JOIN encounter en ON en.id=e.encounter_id AND en.group_id=e.group_id
            WHERE n.user_id=? AND (?::uuid IS NULL OR n.group_id=?) AND (NOT ? OR n.read_at IS NULL)
              AND (?::timestamptz IS NULL OR (n.created_at,n.id)<(?,?::uuid))
            ORDER BY n.created_at DESC,n.id DESC LIMIT ?
            """,this::mapItem,userId,groupId,groupId,unread,
                cursor==null?null:Timestamp.from(cursor.time()),cursor==null?null:Timestamp.from(cursor.time()),cursor==null?null:cursor.id(),fetch);
    }

    public long unreadCount(UUID userId,UUID groupId){
        Long value=jdbc.queryForObject("""
            SELECT count(*) FROM notification n
            JOIN group_member gm ON gm.group_id=n.group_id AND gm.user_id=n.user_id AND gm.status='ACTIVE'
            JOIN care_group g ON g.id=n.group_id AND g.status='ACTIVE'
            WHERE n.user_id=? AND n.read_at IS NULL AND (?::uuid IS NULL OR n.group_id=?)
            """,Long.class,userId,groupId,groupId);return value==null?0:value;
    }
    public Optional<NotificationDtos.Item> item(UUID id){return jdbc.query("""
            SELECT n.id,n.group_id,n.event_id,e.event_type,n.title,n.body,n.read_at,n.created_at,
              CASE WHEN e.occurrence_id IS NOT NULL AND o.id IS NOT NULL THEN 'TASK'
                   WHEN e.encounter_id IS NOT NULL AND en.deleted_at IS NULL THEN 'ENCOUNTER' END target_type,
              CASE WHEN e.occurrence_id IS NOT NULL AND o.id IS NOT NULL THEN e.occurrence_id
                   WHEN e.encounter_id IS NOT NULL AND en.deleted_at IS NULL THEN e.encounter_id END target_id
            FROM notification n JOIN notification_event e ON e.id=n.event_id
            LEFT JOIN task_occurrence o ON o.id=e.occurrence_id AND o.group_id=e.group_id
            LEFT JOIN encounter en ON en.id=e.encounter_id AND en.group_id=e.group_id WHERE n.id=?
            """,this::mapItem,id).stream().findFirst();}

    public Optional<Owner> owner(UUID id){return jdbc.query("SELECT user_id,group_id FROM notification WHERE id=?",
            (rs,n)->new Owner(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)),id).stream().findFirst();}
    public boolean activeMembership(UUID groupId,UUID userId){Integer n=jdbc.queryForObject("""
            SELECT count(*) FROM group_member m JOIN care_group g ON g.id=m.group_id
            WHERE m.group_id=? AND m.user_id=? AND m.status='ACTIVE' AND g.status='ACTIVE'
            """,Integer.class,groupId,userId);return n!=null&&n>0;}
    public Instant markRead(UUID id,Instant now){jdbc.update("UPDATE notification SET read_at=COALESCE(read_at,?) WHERE id=?",Timestamp.from(now),id);
        return jdbc.queryForObject("SELECT read_at FROM notification WHERE id=?",Instant.class,id);}

    public Preference preference(UUID userId){
        jdbc.update("INSERT INTO notification_preference(user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING",userId);
        return jdbc.queryForObject("SELECT handoff_repeat,version FROM notification_preference WHERE user_id=?",
                (rs,n)->new Preference(rs.getString(1),rs.getLong(2)),userId);
    }
    public boolean updatePreference(UUID userId,long version,String repeat){return jdbc.update("""
            UPDATE notification_preference SET handoff_repeat=?,version=version+1 WHERE user_id=? AND version=?
            """,repeat,userId,version)==1;}

    public void endpointLock(byte[] hash){jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)",Object.class,ByteBuffer.wrap(hash).getLong());}
    public Optional<Subscription> activeByHash(byte[] hash){return jdbc.query("""
            SELECT id,user_id,endpoint,p256dh,auth_secret,enabled,created_at,last_seen_at FROM push_subscription
            WHERE endpoint_hash=? AND enabled FOR UPDATE
            """,this::mapSubscription,hash).stream().findFirst();}
    public void touch(UUID id,String endpoint,String p256dh,String auth,Instant now){jdbc.update("""
            UPDATE push_subscription SET endpoint=?,p256dh=?,auth_secret=?,last_seen_at=? WHERE id=? AND enabled
            """,endpoint,p256dh,auth,Timestamp.from(now),id);}
    public void disable(UUID id){jdbc.update("UPDATE push_subscription SET enabled=false WHERE id=?",id);cancelDeliveries(id);}
    public UUID insertSubscription(UUID userId,String endpoint,byte[] hash,String p256dh,String auth,Instant now){UUID id=UUID.randomUUID();jdbc.update("""
            INSERT INTO push_subscription(id,user_id,endpoint,endpoint_hash,p256dh,auth_secret,last_seen_at)
            VALUES (?,?,?,?,?,?,?)
            """,id,userId,endpoint,hash,p256dh,auth,Timestamp.from(now));return id;}
    public List<NotificationDtos.SubscriptionMeta> subscriptions(UUID userId){return jdbc.query("""
            SELECT id,enabled,created_at,last_seen_at FROM push_subscription WHERE user_id=? AND enabled ORDER BY created_at,id
            """,(rs,n)->new NotificationDtos.SubscriptionMeta(rs.getObject(1,UUID.class),rs.getBoolean(2),rs.getTimestamp(3).toInstant().atZone(KST).toOffsetDateTime(),rs.getTimestamp(4).toInstant().atZone(KST).toOffsetDateTime()),userId);}
    public Optional<Subscription> subscription(UUID id){return jdbc.query("""
            SELECT id,user_id,endpoint,p256dh,auth_secret,enabled,created_at,last_seen_at FROM push_subscription WHERE id=?
            """,this::mapSubscription,id).stream().findFirst();}
    public void cancelDeliveries(UUID subscriptionId){jdbc.update("""
            UPDATE notification_delivery SET status='CANCELED',lease_token=NULL,lease_until=NULL
            WHERE subscription_id=? AND status IN ('PENDING','FAILED','RUNNING')
            """,subscriptionId);}

    private NotificationDtos.Item mapItem(ResultSet rs,int row)throws SQLException{
        String type=rs.getString("target_type");UUID group=rs.getObject("group_id",UUID.class);
        NotificationDtos.Target target=type==null?null:new NotificationDtos.Target(type,rs.getObject("target_id",UUID.class),group);
        Timestamp read=rs.getTimestamp("read_at");
        return new NotificationDtos.Item(rs.getObject("id",UUID.class),group,rs.getObject("event_id",UUID.class),
                rs.getString("event_type"),rs.getString("title"),rs.getString("body"),read==null?null:read.toInstant().atZone(KST).toOffsetDateTime(),
                rs.getTimestamp("created_at").toInstant().atZone(KST).toOffsetDateTime(),target);
    }
    private Subscription mapSubscription(ResultSet rs,int row)throws SQLException{return new Subscription(rs.getObject("id",UUID.class),
            rs.getObject("user_id",UUID.class),rs.getString("endpoint"),rs.getString("p256dh"),rs.getString("auth_secret"),
            rs.getBoolean("enabled"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("last_seen_at").toInstant());}
    public record Owner(UUID userId,UUID groupId){}
    public record Preference(String repeat,long version){}
    public record Subscription(UUID id,UUID userId,String endpoint,String p256dh,String auth,boolean enabled,Instant createdAt,Instant lastSeenAt){}
}
