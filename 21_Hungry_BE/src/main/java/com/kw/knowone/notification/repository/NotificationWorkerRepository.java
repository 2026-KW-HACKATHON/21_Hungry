package com.kw.knowone.notification.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class NotificationWorkerRepository {
    private final JdbcTemplate jdbc;
    public NotificationWorkerRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @Transactional
    public List<EventClaim> claimEvents(int limit,Instant now,Duration lease){return jdbc.query("""
        WITH picked AS (
          SELECT id FROM notification_event
          WHERE (status='PENDING' AND due_at<=?) OR (status='RUNNING' AND lease_until<?)
          ORDER BY due_at,id FOR UPDATE SKIP LOCKED LIMIT ?
        )
        UPDATE notification_event e SET status='RUNNING',attempt_count=attempt_count+1,
          lease_token=gen_random_uuid(),lease_until=? FROM picked p WHERE e.id=p.id
        RETURNING e.id,e.group_id,e.event_type,e.occurrence_id,e.handoff_id,e.encounter_id,e.target_user_id,
          e.expected_task_version,e.attempt_count,e.lease_token
        """,this::mapEvent,Timestamp.from(now),Timestamp.from(now),limit,Timestamp.from(now.plus(lease)));}

    @Transactional
    public boolean expand(EventClaim claim,Instant now){
        Integer owned=jdbc.queryForObject("SELECT count(*) FROM notification_event WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>?",
                Integer.class,claim.id(),claim.leaseToken(),Timestamp.from(now));if(owned==null||owned!=1)return false;
        if(!validEvent(claim)){cancelEvent(claim);return true;}
        List<UUID> recipients=recipients(claim);
        String[] copy=copy(claim.type());
        for(UUID userId:recipients){UUID notificationId=UUID.randomUUID();jdbc.update("""
            INSERT INTO notification(id,group_id,event_id,user_id,title,body,created_at)
            VALUES (?,?,?,?,?,?,?) ON CONFLICT (event_id,user_id) DO NOTHING
            """,notificationId,claim.groupId(),claim.id(),userId,copy[0],copy[1],Timestamp.from(now));
            jdbc.update("""
              INSERT INTO notification_delivery(id,notification_id,user_id,subscription_id,next_attempt_at)
              SELECT gen_random_uuid(),n.id,n.user_id,s.id,? FROM notification n
              JOIN push_subscription s ON s.user_id=n.user_id AND s.enabled
              WHERE n.event_id=? AND n.user_id=? ON CONFLICT (notification_id,subscription_id) DO NOTHING
              """,Timestamp.from(now),claim.id(),userId);
        }
        return jdbc.update("""
          UPDATE notification_event SET status='EXPANDED',lease_token=NULL,lease_until=NULL
          WHERE id=? AND status='RUNNING' AND lease_token=?
          """,claim.id(),claim.leaseToken())==1;
    }

    @Transactional
    public void eventFailure(EventClaim claim,Instant now,int maxAttempts){if(claim.attempts()>=maxAttempts)jdbc.update("""
        UPDATE notification_event SET status='FAILED',lease_token=NULL,lease_until=NULL WHERE id=? AND status='RUNNING' AND lease_token=?
        """,claim.id(),claim.leaseToken());else jdbc.update("""
        UPDATE notification_event SET status='PENDING',due_at=?,lease_token=NULL,lease_until=NULL WHERE id=? AND status='RUNNING' AND lease_token=?
        """,Timestamp.from(now.plusSeconds(backoff(claim.attempts()))),claim.id(),claim.leaseToken());}

    @Transactional
    public List<DeliveryClaim> claimDeliveries(int limit,Instant now,Duration lease){return jdbc.query("""
        WITH picked AS (
          SELECT id FROM notification_delivery
          WHERE (status IN ('PENDING','FAILED') AND next_attempt_at<=?) OR (status='RUNNING' AND lease_until<?)
          ORDER BY next_attempt_at,id FOR UPDATE SKIP LOCKED LIMIT ?
        )
        UPDATE notification_delivery d SET status='RUNNING',attempt_count=attempt_count+1,
          lease_token=gen_random_uuid(),lease_until=? FROM picked p WHERE d.id=p.id
        RETURNING d.id,d.notification_id,d.user_id,d.subscription_id,d.attempt_count,d.lease_token
        """,this::mapDelivery,Timestamp.from(now),Timestamp.from(now),limit,Timestamp.from(now.plus(lease)));}

    @Transactional
    public Optional<Outbound> prepare(DeliveryClaim claim,Instant now){List<Outbound> values=jdbc.query("""
        SELECT s.endpoint,s.p256dh,s.auth_secret,n.event_id,n.title,n.body
        FROM notification_delivery d JOIN notification n ON n.id=d.notification_id AND n.user_id=d.user_id
        JOIN notification_event e ON e.id=n.event_id JOIN push_subscription s ON s.id=d.subscription_id AND s.user_id=d.user_id
        JOIN app_user u ON u.id=d.user_id JOIN group_member gm ON gm.group_id=n.group_id AND gm.user_id=d.user_id
        JOIN care_group g ON g.id=n.group_id
        LEFT JOIN task_occurrence o ON o.id=e.occurrence_id AND o.group_id=e.group_id
        LEFT JOIN handoff_request h ON h.id=e.handoff_id AND h.group_id=e.group_id
        LEFT JOIN encounter en ON en.id=e.encounter_id AND en.group_id=e.group_id
        WHERE d.id=? AND d.status='RUNNING' AND d.lease_token=? AND d.lease_until>?
          AND s.enabled AND u.status='ACTIVE' AND gm.status='ACTIVE' AND g.status='ACTIVE' AND e.status='EXPANDED'
          AND (e.encounter_id IS NULL OR en.deleted_at IS NULL)
          AND (e.event_type NOT IN ('RECORD_READY','RECORD_REVIEW_REQUIRED') OR EXISTS(
            SELECT 1 FROM encounter_revision er WHERE er.encounter_id=e.encounter_id AND er.is_current
              AND er.id::text=e.payload->>'revisionId'))
          AND (e.event_type<>'DAILY_DIGEST' OR (COALESCE((SELECT p.handoff_repeat FROM notification_preference p WHERE p.user_id=d.user_id),'DAILY')='DAILY'
            AND EXISTS(SELECT 1 FROM handoff_request dh JOIN task_occurrence doo ON doo.id=dh.occurrence_id
              WHERE dh.group_id=e.group_id AND dh.status='OPEN' AND doo.status='PENDING' AND doo.starts_at>now()
                AND NOT EXISTS(SELECT 1 FROM notification dn JOIN notification_event de ON de.id=dn.event_id
                  WHERE dn.user_id=d.user_id AND de.handoff_id=dh.id AND de.event_type='HANDOFF_OPEN'
                    AND (dn.created_at AT TIME ZONE 'Asia/Seoul')::date=(now() AT TIME ZONE 'Asia/Seoul')::date))))
          AND (e.occurrence_id IS NULL OR
            CASE WHEN e.event_type IN ('DUE_30M','DUE_NOW','TASK_ASSIGNED','TASK_REOPENED')
                 THEN o.status='PENDING' AND o.version=e.expected_task_version AND o.assignee_user_id=d.user_id
                 WHEN e.event_type='OVERDUE' THEN o.status='PENDING' AND o.version=e.expected_task_version
                 WHEN e.event_type='HANDOFF_OPEN' THEN o.status='PENDING' AND h.status='OPEN' AND o.version=e.expected_task_version
                 WHEN e.event_type='HANDOFF_ACCEPTED' THEN h.status='ACCEPTED'
                 WHEN e.event_type='TASK_COMPLETED' THEN o.status='COMPLETED'
                 ELSE true END)
        """,(rs,row)->new Outbound(rs.getString(1),rs.getString(2),rs.getString(3),rs.getObject(4,UUID.class),rs.getString(5),rs.getString(6)),
                claim.id(),claim.leaseToken(),Timestamp.from(now));
        if(values.isEmpty())cancelDelivery(claim);return values.stream().findFirst();}

    @Transactional
    public void sent(DeliveryClaim claim,Instant now,int status){jdbc.update("""
        UPDATE notification_delivery SET status='SENT',sent_at=?,last_http_status=?,lease_token=NULL,lease_until=NULL
        WHERE id=? AND status='RUNNING' AND lease_token=?
        """,Timestamp.from(now),status,claim.id(),claim.leaseToken());}

    @Transactional
    public void deliveryFailure(DeliveryClaim claim,Instant now,int status,Long retryAfter,int maxAttempts,boolean permanent){
        if(status==404||status==410){jdbc.update("UPDATE push_subscription SET enabled=false WHERE id=?",claim.subscriptionId());jdbc.update("""
            UPDATE notification_delivery SET status='CANCELED',lease_token=NULL,lease_until=NULL
            WHERE subscription_id=? AND status IN ('PENDING','FAILED','RUNNING')
            """,claim.subscriptionId());return;}
        if(permanent||claim.attempts()>=maxAttempts){jdbc.update("""
            UPDATE notification_delivery SET status='FAILED',last_http_status=?,next_attempt_at='infinity',lease_token=NULL,lease_until=NULL
            WHERE id=? AND status='RUNNING' AND lease_token=?
            """,status==0?null:status,claim.id(),claim.leaseToken());return;}
        long delay=status==429&&retryAfter!=null?Math.min(3600,Math.max(1,retryAfter)):backoff(claim.attempts());
        jdbc.update("""
          UPDATE notification_delivery SET status='FAILED',last_http_status=?,next_attempt_at=?,lease_token=NULL,lease_until=NULL
          WHERE id=? AND status='RUNNING' AND lease_token=?
          """,status==0?null:status,Timestamp.from(now.plusSeconds(delay)),claim.id(),claim.leaseToken());}

    private boolean validEvent(EventClaim e){Integer value=jdbc.queryForObject("""
      SELECT count(*) FROM notification_event ne JOIN care_group g ON g.id=ne.group_id AND g.status='ACTIVE'
      LEFT JOIN task_occurrence o ON o.id=ne.occurrence_id AND o.group_id=ne.group_id
      LEFT JOIN handoff_request h ON h.id=ne.handoff_id AND h.group_id=ne.group_id
      LEFT JOIN encounter en ON en.id=ne.encounter_id AND en.group_id=ne.group_id
      WHERE ne.id=? AND (ne.encounter_id IS NULL OR en.deleted_at IS NULL)
       AND (ne.event_type NOT IN ('RECORD_READY','RECORD_REVIEW_REQUIRED') OR EXISTS(
         SELECT 1 FROM encounter_revision er WHERE er.encounter_id=ne.encounter_id AND er.is_current
           AND er.id::text=ne.payload->>'revisionId'))
       AND (ne.target_user_id IS NULL OR EXISTS(SELECT 1 FROM group_member m WHERE m.group_id=ne.group_id AND m.user_id=ne.target_user_id AND m.status='ACTIVE'))
       AND (ne.occurrence_id IS NULL OR CASE
         WHEN ne.event_type='HANDOFF_OPEN' THEN o.status='PENDING' AND h.status='OPEN' AND o.version=ne.expected_task_version
         WHEN ne.event_type='HANDOFF_ACCEPTED' THEN h.status='ACCEPTED'
         WHEN ne.event_type='TASK_COMPLETED' THEN o.status='COMPLETED'
         WHEN ne.event_type IN ('DUE_30M','DUE_NOW','TASK_ASSIGNED','TASK_REOPENED','OVERDUE') THEN o.status='PENDING' AND o.version=ne.expected_task_version
         ELSE true END)
       AND (ne.event_type<>'DAILY_DIGEST' OR (COALESCE((SELECT p.handoff_repeat FROM notification_preference p WHERE p.user_id=ne.target_user_id),'DAILY')='DAILY' AND EXISTS(
         SELECT 1 FROM handoff_request dh JOIN task_occurrence doo ON doo.id=dh.occurrence_id
         WHERE dh.group_id=ne.group_id AND dh.status='OPEN' AND doo.status='PENDING' AND doo.starts_at>now()
           AND NOT EXISTS(SELECT 1 FROM notification dn JOIN notification_event de ON de.id=dn.event_id
             WHERE dn.user_id=ne.target_user_id AND de.handoff_id=dh.id AND de.event_type='HANDOFF_OPEN'
               AND (dn.created_at AT TIME ZONE 'Asia/Seoul')::date=(now() AT TIME ZONE 'Asia/Seoul')::date))))
      """,Integer.class,e.id());return value!=null&&value==1;}
    private List<UUID> recipients(EventClaim e){if(e.targetUserId()!=null)return List.of(e.targetUserId());
        if(List.of("DUE_30M","DUE_NOW","TASK_ASSIGNED","TASK_REOPENED").contains(e.type()))return jdbc.query("""
            SELECT o.assignee_user_id FROM task_occurrence o JOIN group_member m ON m.group_id=o.group_id AND m.user_id=o.assignee_user_id AND m.status='ACTIVE'
            WHERE o.id=? AND o.assignee_user_id IS NOT NULL
            """,(rs,n)->rs.getObject(1,UUID.class),e.occurrenceId());
        return jdbc.query("SELECT user_id FROM group_member WHERE group_id=? AND status='ACTIVE' ORDER BY user_id",
                (rs,n)->rs.getObject(1,UUID.class),e.groupId());}
    private void cancelEvent(EventClaim e){jdbc.update("UPDATE notification_event SET status='CANCELED',lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?",e.id(),e.leaseToken());}
    private void cancelDelivery(DeliveryClaim d){jdbc.update("UPDATE notification_delivery SET status='CANCELED',lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?",d.id(),d.leaseToken());}
    private long backoff(int attempts){return Math.min(900,15L*(1L<<Math.min(6,Math.max(0,attempts-1))));}
    private String[] copy(String type){return switch(type){
        case "RECORD_READY"->new String[]{"진료 기록 분석 완료","진료 기록을 확인해 주세요."};
        case "RECORD_REVIEW_REQUIRED"->new String[]{"확인이 필요한 진료 기록","추출 결과를 확인해 주세요."};
        case "TASK_ASSIGNED"->new String[]{"새 돌봄 일정","담당 일정이 배정되었습니다."};
        case "HANDOFF_OPEN"->new String[]{"담당자가 필요한 일정","알림함에서 일정을 확인해 주세요."};
        case "HANDOFF_ACCEPTED"->new String[]{"인계 수락","일정 담당자가 정해졌습니다."};
        case "DUE_30M"->new String[]{"곧 시작할 돌봄 일정","30분 뒤 일정이 시작됩니다."};
        case "DUE_NOW"->new String[]{"돌봄 일정 시작","지금 시작하는 일정이 있습니다."};
        case "OVERDUE"->new String[]{"확인이 필요한 일정","경과한 일정을 확인해 주세요."};
        case "DAILY_DIGEST"->new String[]{"오늘의 미배정 일정","담당자가 필요한 일정을 확인해 주세요."};
        default->new String[]{"돌봄 일정 알림","알림함에서 변경 내용을 확인해 주세요."};};}
    private EventClaim mapEvent(ResultSet rs,int n)throws SQLException{return new EventClaim(rs.getObject("id",UUID.class),rs.getObject("group_id",UUID.class),rs.getString("event_type"),rs.getObject("occurrence_id",UUID.class),rs.getObject("handoff_id",UUID.class),rs.getObject("encounter_id",UUID.class),rs.getObject("target_user_id",UUID.class),rs.getObject("expected_task_version",Long.class),rs.getInt("attempt_count"),rs.getObject("lease_token",UUID.class));}
    private DeliveryClaim mapDelivery(ResultSet rs,int n)throws SQLException{return new DeliveryClaim(rs.getObject("id",UUID.class),rs.getObject("notification_id",UUID.class),rs.getObject("user_id",UUID.class),rs.getObject("subscription_id",UUID.class),rs.getInt("attempt_count"),rs.getObject("lease_token",UUID.class));}
    public record EventClaim(UUID id,UUID groupId,String type,UUID occurrenceId,UUID handoffId,UUID encounterId,UUID targetUserId,Long expectedVersion,int attempts,UUID leaseToken){}
    public record DeliveryClaim(UUID id,UUID notificationId,UUID userId,UUID subscriptionId,int attempts,UUID leaseToken){}
    public record Outbound(String endpoint,String p256dh,String auth,UUID eventId,String title,String body){}
}
