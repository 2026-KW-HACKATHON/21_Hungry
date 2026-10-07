package com.kw.knowone;

import static org.junit.jupiter.api.Assertions.*;

import com.kw.knowone.common.web.ApiException;
import com.kw.knowone.group.repository.GroupEventRepository;
import com.kw.knowone.notification.dto.NotificationDtos;
import com.kw.knowone.notification.push.PushEndpointPolicy;
import com.kw.knowone.notification.repository.NotificationRepository;
import com.kw.knowone.notification.repository.NotificationWorkerRepository;
import com.kw.knowone.notification.service.NotificationService;
import com.kw.knowone.notification.service.NotificationScheduleService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

@ActiveProfiles("test") @SpringBootTest
@Sql(scripts={"classpath:phase2-fixture.sql","classpath:phase3-fixture.sql"},executionPhase=Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class Phase7IntegrationTests {
    static final UUID GROUP=UUID.fromString("10000000-0000-4000-8000-000000000001");
    static final UUID USER=UUID.fromString("00000000-0000-4000-8000-000000000002");
    static final UUID OTHER=UUID.fromString("00000000-0000-4000-8000-000000000006");
    static final UUID TASK=UUID.fromString("40000000-0000-4000-8000-000000000001");
    @Autowired JdbcTemplate jdbc;@Autowired NotificationService service;@Autowired NotificationRepository notifications;
    @Autowired NotificationWorkerRepository workers;@Autowired GroupEventRepository events;@Autowired PushEndpointPolicy endpoints;@Autowired Clock clock;

    @BeforeEach void clear(){jdbc.update("DELETE FROM notification_delivery");jdbc.update("DELETE FROM notification");jdbc.update("DELETE FROM notification_event");jdbc.update("DELETE FROM push_subscription");jdbc.update("DELETE FROM notification_preference");}

    @Test void preferencesDefaultToDailyAndUseOptimisticVersion(){var initial=service.preference(USER);assertEquals("DAILY",initial.handoffRepeat());assertEquals(0,initial.version());
        var changed=service.updatePreference(USER,new NotificationDtos.PreferenceUpdate(0L,"ONCE"));assertEquals("ONCE",changed.handoffRepeat());assertEquals(1,changed.version());
        ApiException stale=assertThrows(ApiException.class,()->service.updatePreference(USER,new NotificationDtos.PreferenceUpdate(0L,"DAILY")));assertEquals("VERSION_CONFLICT",stale.code());}

    @Test void endpointAndKeysRejectUnsafeInput(){assertEquals("INVALID_PUSH_ENDPOINT",assertThrows(ApiException.class,()->endpoints.validate("http://169.254.169.254/push")).code());
        String auth=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
        var bad=new NotificationDtos.SubscriptionCreate("https://fcm.googleapis.com/push",new NotificationDtos.SubscriptionKeys("AA",auth));
        assertEquals("VALIDATION_ERROR",assertThrows(ApiException.class,()->service.subscribe(USER,bad)).code());}

    @Test void sameEndpointMovesAccountsWithoutRewritingHistoricalSubscription()throws Exception{String endpoint="https://fcm.googleapis.com/fcm/send/account-transfer";
        byte[] point=HexFormat.of().parseHex("046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5");
        var request=new NotificationDtos.SubscriptionCreate(endpoint,new NotificationDtos.SubscriptionKeys(Base64.getUrlEncoder().withoutPadding().encodeToString(point),Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16])));
        var old=service.subscribe(USER,request);assertTrue(old.created());var replay=service.subscribe(USER,request);assertFalse(replay.created());assertEquals(old.value().id(),replay.value().id());event("TASK_ASSIGNED","task:"+TASK+":assignment:0",USER,0L);workers.expand(workers.claimEvents(1,clock.instant(),Duration.ofMinutes(2)).getFirst(),clock.instant());
        var replacement=service.subscribe(OTHER,request);assertTrue(replacement.created());assertNotEquals(old.value().id(),replacement.value().id());assertFalse(notifications.subscription(old.value().id()).orElseThrow().enabled());
        assertEquals(USER,notifications.subscription(old.value().id()).orElseThrow().userId());assertEquals("CANCELED",jdbc.queryForObject("SELECT status FROM notification_delivery WHERE subscription_id=?",String.class,old.value().id()));
        assertEquals(403,assertThrows(ApiException.class,()->service.unsubscribe(USER,replacement.value().id())).status().value());}

    @Test void outboxExpansionCreatesInboxAndOneDeliveryPerDeviceWithoutDuplicates()throws Exception{UUID sub=subscription(USER,"a");
        event("TASK_ASSIGNED","task:"+TASK+":assignment:0",USER,0L);var claim=workers.claimEvents(1,clock.instant(),Duration.ofMinutes(2)).getFirst();assertTrue(workers.expand(claim,clock.instant()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM notification WHERE event_id=?",Integer.class,claim.id()));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM notification_delivery WHERE subscription_id=?",Integer.class,sub));
        assertFalse(workers.expand(claim,clock.instant()));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM notification",Integer.class));}

    @Test void inboxCursorUnreadAndFirstReadTimestampAreStable()throws Exception{event("TASK_ASSIGNED","task:"+TASK+":assignment:0",USER,0L);workers.expand(workers.claimEvents(1,clock.instant(),Duration.ofMinutes(2)).getFirst(),clock.instant());
        var page=service.list(USER,GROUP,true,1,null);assertEquals(1,page.items().size());assertEquals(1,page.unreadCount());UUID id=page.items().getFirst().id();var first=service.read(USER,id).readAt();var second=service.read(USER,id).readAt();assertEquals(first,second);assertEquals(0,service.list(USER,GROUP,true,20,null).unreadCount());
        assertEquals(403,assertThrows(ApiException.class,()->service.read(OTHER,id)).status().value());jdbc.update("UPDATE group_member SET status='LEFT',left_at=now() WHERE group_id=? AND user_id=?",GROUP,USER);assertTrue(service.list(USER,null,false,20,null).items().isEmpty());}

    @Test void deliveryFencingAndGoneSubscriptionHandlingArePerDevice()throws Exception{UUID first=subscription(USER,"one"),second=subscription(USER,"two");event("TASK_ASSIGNED","task:"+TASK+":assignment:0",USER,0L);workers.expand(workers.claimEvents(1,clock.instant(),Duration.ofMinutes(2)).getFirst(),clock.instant());
        var claims=workers.claimDeliveries(2,clock.instant(),Duration.ofSeconds(1));var gone=claims.stream().filter(c->c.subscriptionId().equals(first)).findFirst().orElseThrow();workers.deliveryFailure(gone,clock.instant(),410,null,3,true);
        assertFalse(notifications.subscription(first).orElseThrow().enabled());assertTrue(notifications.subscription(second).orElseThrow().enabled());
        var live=claims.stream().filter(c->c.subscriptionId().equals(second)).findFirst().orElseThrow();jdbc.update("UPDATE notification_delivery SET lease_until=now()-interval '1 second' WHERE id=?",live.id());var reclaimed=workers.claimDeliveries(1,clock.instant(),Duration.ofMinutes(2)).getFirst();workers.sent(live,clock.instant(),201);
        assertEquals("RUNNING",jdbc.queryForObject("SELECT status FROM notification_delivery WHERE id=?",String.class,reclaimed.id()));workers.sent(reclaimed,clock.instant(),201);assertEquals("SENT",jdbc.queryForObject("SELECT status FROM notification_delivery WHERE id=?",String.class,reclaimed.id()));}

    @Test void scheduleVersionChangeCancelsOldEventsAndCreatesCurrentFutureSet(){Instant now=clock.instant();events.syncOccurrenceNotifications(TASK,now);assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM notification_event WHERE occurrence_id=? AND status='PENDING'",Integer.class,TASK));
        jdbc.update("UPDATE task_occurrence SET starts_at=starts_at+interval '1 hour',ends_at=ends_at+interval '1 hour',version=version+1 WHERE id=?",TASK);events.syncOccurrenceNotifications(TASK,now);
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM notification_event WHERE occurrence_id=? AND expected_task_version=0 AND status='CANCELED'",Integer.class,TASK));assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM notification_event WHERE occurrence_id=? AND expected_task_version=1 AND status='PENDING'",Integer.class,TASK));}

    @Test void dailyDigestExcludesHandoffAlreadyNotifiedToday(){Instant now=clock.instant();jdbc.update("UPDATE task_occurrence SET assignee_user_id=NULL,assignment_origin=NULL,version=version+1 WHERE id=?",TASK);UUID handoff=UUID.randomUUID();jdbc.update("INSERT INTO handoff_request(id,group_id,occurrence_id,reason,status) VALUES (?,?,?,'NO_CANDIDATE','OPEN')",handoff,GROUP,TASK);
        jdbc.update("INSERT INTO notification_event(id,group_id,event_type,event_key,occurrence_id,handoff_id,expected_task_version,due_at) VALUES (gen_random_uuid(),?,'HANDOFF_OPEN',?,?,?,1,?)",GROUP,"handoff:"+handoff+":open",TASK,handoff,Timestamp.from(now));
        workers.expand(workers.claimEvents(1,now,Duration.ofMinutes(2)).getFirst(),now);events.createDailyDigests(now);assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM notification_event WHERE event_type='DAILY_DIGEST' AND target_user_id=?",Integer.class,USER));}

    @Test void kstDigestBoundaryAndRetryPoliciesAreDeterministic()throws Exception{assertFalse(NotificationScheduleService.digestWindow(Instant.parse("2026-10-06T23:59:59Z")));assertTrue(NotificationScheduleService.digestWindow(Instant.parse("2026-10-07T00:00:00Z")));
        subscription(USER,"retry");event("TASK_ASSIGNED","task:"+TASK+":assignment:0",USER,0L);workers.expand(workers.claimEvents(1,clock.instant(),Duration.ofMinutes(2)).getFirst(),clock.instant());var claim=workers.claimDeliveries(1,clock.instant(),Duration.ofMinutes(2)).getFirst();workers.deliveryFailure(claim,clock.instant(),429,120L,3,false);
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM notification_delivery WHERE id=?",String.class,claim.id()));assertTrue(jdbc.queryForObject("SELECT next_attempt_at FROM notification_delivery WHERE id=?",Instant.class,claim.id()).isAfter(clock.instant().plusSeconds(100)));
        jdbc.update("UPDATE notification_delivery SET next_attempt_at=now()-interval '1 second',attempt_count=2 WHERE id=?",claim.id());var last=workers.claimDeliveries(1,clock.instant(),Duration.ofMinutes(2)).getFirst();workers.deliveryFailure(last,clock.instant(),0,null,3,false);assertTrue(jdbc.queryForObject("SELECT next_attempt_at='infinity' FROM notification_delivery WHERE id=?",Boolean.class,last.id()));}

    private void event(String type,String key,UUID target,Long version){jdbc.update("INSERT INTO notification_event(id,group_id,event_type,event_key,occurrence_id,target_user_id,expected_task_version,due_at) VALUES (gen_random_uuid(),?,?,?,?,?,?,?)",GROUP,type,key,TASK,target,version,Timestamp.from(clock.instant()));}
    private UUID subscription(UUID user,String marker)throws Exception{UUID id=UUID.randomUUID();String endpoint="https://fcm.googleapis.com/push/"+marker;byte[] hash=MessageDigest.getInstance("SHA-256").digest(endpoint.getBytes(StandardCharsets.UTF_8));jdbc.update("INSERT INTO push_subscription(id,user_id,endpoint,endpoint_hash,p256dh,auth_secret) VALUES (?,?,?,?,?,?)",id,user,endpoint,hash,"key","auth");return id;}
}
