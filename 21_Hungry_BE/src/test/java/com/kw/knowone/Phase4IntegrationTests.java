package com.kw.knowone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Clock;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import com.kw.knowone.task.service.TaskGenerationService;
import com.kw.knowone.task.service.ScheduleAssignmentService;
import com.kw.knowone.task.repository.TaskRepository;
import com.kw.knowone.group.repository.GroupEventRepository;
import com.kw.knowone.common.schedule.ScheduleMutationService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql(scripts={"classpath:phase2-fixture.sql","classpath:phase3-fixture.sql"},executionPhase=Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class Phase4IntegrationTests {
    private static final UUID GROUP=UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID CAREGIVER=UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID SECOND=UUID.fromString("00000000-0000-4000-8000-000000000004");
    private static final UUID EXISTING=UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    @Value("${local.server.port}")int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired TaskGenerationService generator;
    @Autowired TransactionTemplate transaction;
    @Autowired TaskRepository taskRepository;@Autowired ScheduleAssignmentService assignmentService;
    @Autowired GroupEventRepository groupEvents;@Autowired ScheduleMutationService scheduleMutations;@Autowired Clock clock;
    final HttpClient client=HttpClient.newHttpClient();

    @Test void dailyWeeklyHorizonAndGeneratorRetryAreIdempotent()throws Exception{
        String token=login("demo-caregiver-1");LocalDate first=LocalDate.now(KST).plusDays(1);
        JsonNode daily=data(send("POST","/api/v1/care-groups/"+GROUP+"/task-series",recurring("DAILY",first,first.plusDays(3),"[]","매일"),token,"daily"));
        assertEquals(4,daily.get("occurrences").size());UUID dailyId=UUID.fromString(daily.get("seriesId").asText());
        int weekday=first.getDayOfWeek().getValue();
        JsonNode weekly=data(send("POST","/api/v1/care-groups/"+GROUP+"/task-series",recurring("WEEKLY",first,first.plusDays(13),"["+weekday+"]","주간"),token,"weekly"));
        assertEquals(2,weekly.get("occurrences").size());UUID weeklyId=UUID.fromString(weekly.get("seriesId").asText());
        int before=count(dailyId)+count(weeklyId);generator.scheduledGenerate();generator.scheduledGenerate();
        CompletableFuture<Void> firstRun=CompletableFuture.runAsync(generator::scheduledGenerate);
        CompletableFuture<Void> secondRun=CompletableFuture.runAsync(generator::scheduledGenerate);
        CompletableFuture.allOf(firstRun,secondRun).join();
        new TaskGenerationService(taskRepository,assignmentService,groupEvents,scheduleMutations,clock).scheduledGenerate();
        assertEquals(before,count(dailyId)+count(weeklyId));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM (SELECT series_id,anchor_date,count(*) c FROM task_occurrence WHERE series_id IN (?,?) GROUP BY series_id,anchor_date HAVING count(*)>1) d",Integer.class,dailyId,weeklyId));
        assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE event_type='TASK_CREATED' AND entity_id IN (SELECT id FROM task_occurrence WHERE series_id IN (?,?))",Integer.class,dailyId,weeklyId));
    }

    @Test void occurrenceAndSeriesEditKeepAnchorThenDeletionUsesAnchorBoundary()throws Exception{
        String token=login("demo-caregiver-1");LocalDate first=LocalDate.now(KST).plusDays(1);
        JsonNode created=data(send("POST","/api/v1/care-groups/"+GROUP+"/task-series",recurring("DAILY",first,first.plusDays(3),"[]","수정"),token,"edit-create"));
        JsonNode selected=created.get("occurrences").get(0);UUID id=UUID.fromString(selected.get("id").asText());UUID series=UUID.fromString(created.get("seriesId").asText());
        String moved="{\"scope\":\"OCCURRENCE\",\"expectedVersion\":0,\"patch\":{\"startsAt\":\""+first.plusDays(1)+"T18:00:00+09:00\",\"endsAt\":\""+first.plusDays(1)+"T18:30:00+09:00\"}}";
        JsonNode movedTask=data(send("PATCH","/api/v1/tasks/"+id,moved,token,"move-one")).get("occurrence");
        assertEquals(first.toString(),movedTask.get("anchorDate").asText());assertTrue(movedTask.get("isOverride").asBoolean());
        String previewBody="{\"expectedVersion\":1,\"expectedSeriesVersion\":0,\"patch\":{\"localTime\":\"11:00\"}}";
        JsonNode preview=data(send("POST","/api/v1/tasks/"+id+"/series-edit-preview",previewBody,token,null));
        assertEquals(1,preview.get("counts").get("overwrittenOverrides").asInt());
        String save="{\"scope\":\"SERIES_ALL_PENDING\",\"expectedVersion\":1,\"expectedSeriesVersion\":0,\"previewToken\":"+quote(preview.get("previewToken").asText())+",\"patch\":{\"localTime\":\"11:00\"}}";
        HttpResponse<String> saved=send("PATCH","/api/v1/tasks/"+id,save,token,"edit-series");assertEquals(200,saved.statusCode(),saved.body());
        assertFalse(jdbc.queryForObject("SELECT is_override FROM task_occurrence WHERE id=?",Boolean.class,id));
        long occurrenceVersion=jdbc.queryForObject("SELECT version FROM task_occurrence WHERE id=?",Long.class,id);
        long seriesVersion=jdbc.queryForObject("SELECT version FROM task_series WHERE id=?",Long.class,series);
        String deletePreviewBody="{\"scope\":\"SERIES_FROM_SELECTED\",\"expectedVersion\":"+occurrenceVersion+",\"expectedSeriesVersion\":"+seriesVersion+"}";
        JsonNode deletion=data(send("POST","/api/v1/tasks/"+id+"/deletion-preview",deletePreviewBody,token,null));
        String delete="{\"scope\":\"SERIES_FROM_SELECTED\",\"expectedVersion\":"+occurrenceVersion+",\"expectedSeriesVersion\":"+seriesVersion+",\"previewToken\":"+quote(deletion.get("previewToken").asText())+"}";
        assertEquals(200,send("DELETE","/api/v1/tasks/"+id,delete,token,"delete-series").statusCode());
        assertEquals(first,jdbc.queryForObject("SELECT stop_from_date FROM task_series WHERE id=?",LocalDate.class,series));
        assertEquals(4,jdbc.queryForObject("SELECT count(*) FROM task_occurrence WHERE series_id=? AND status='CANCELED' AND cancel_reason='USER_FUTURE'",Integer.class,series));
    }

    @Test void handoffRequestAcceptListAndHomeUseCurrentTaskData()throws Exception{
        String requester=login("demo-caregiver-1");LocalDate date=LocalDate.now(KST).plusDays(2);
        JsonNode task=data(send("POST","/api/v1/care-groups/"+GROUP+"/task-series",once(date,"19:00","인계"),requester,"handoff-create")).get("occurrences").get(0);
        UUID taskId=UUID.fromString(task.get("id").asText());
        JsonNode requested=data(send("POST","/api/v1/tasks/"+taskId+"/handoffs","{\"expectedVersion\":0}",requester,"handoff-request"));
        UUID handoffId=UUID.fromString(requested.get("handoff").get("id").asText());
        String second=login("demo-caregiver-3"),body="{\"expectedVersion\":0,\"expectedOccurrenceVersion\":1}";
        CompletableFuture<HttpResponse<String>> a=client.sendAsync(request("POST","/api/v1/handoffs/"+handoffId+"/accept",body,requester,"handoff-accept-a"),HttpResponse.BodyHandlers.ofString());
        CompletableFuture<HttpResponse<String>> b=client.sendAsync(request("POST","/api/v1/handoffs/"+handoffId+"/accept",body,second,"handoff-accept-b"),HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> ar=a.join(),br=b.join();assertEquals(List.of(200,409),java.util.stream.Stream.of(ar.statusCode(),br.statusCode()).sorted().toList());
        HttpResponse<String> accepted=ar.statusCode()==200?ar:br;String winner=ar.statusCode()==200?requester:second;
        assertEquals("HANDOFF",data(accepted).get("occurrence").get("assignmentOrigin").asText());
        assertEquals(1,data(send("GET","/api/v1/care-groups/"+GROUP+"/handoffs?status=ACCEPTED",null,winner,null)).get("items").size());
        JsonNode home=data(send("GET","/api/v1/care-groups/"+GROUP+"/home?date="+date,null,winner,null));
        assertEquals(taskId.toString(),home.get("todayMyTasks").get("items").get(0).get("id").asText());assertEquals(0,home.get("reviewEncounterCount").asInt());
    }

    @Test void fourHourBundleUsesPreAssignmentCountsAndDoesNotChain()throws Exception{
        LocalDate date=LocalDate.now(KST).plusDays(1);UUID[] ids=transaction.execute(status->new UUID[]{series(date,"13:00","13시"),series(date,"17:00","17시"),series(date,"21:00","21시")});
        UUID s13=ids[0],s17=ids[1],s21=ids[2];
        generator.scheduledGenerate();
        UUID a13=assignee(s13),a17=assignee(s17),a21=assignee(s21);
        assertEquals(SECOND,a13);assertEquals(a13,a17);assertEquals(CAREGIVER,a21);
    }

    @Test void caregiverLeaveReleasesFutureTasksAndBlocksGroupAccess()throws Exception{
        String token=login("demo-caregiver-1");LocalDate date=LocalDate.now(KST).plusDays(2);
        JsonNode task=data(send("POST","/api/v1/care-groups/"+GROUP+"/task-series",once(date,"20:00","탈퇴"),token,"leave-create")).get("occurrences").get(0);
        UUID assigned=UUID.fromString(task.get("assignee").get("id").asText());String assignedToken=assigned.equals(CAREGIVER)?token:login("demo-caregiver-3");
        jdbc.update("INSERT INTO group_member(id,group_id,user_id,role,priority) VALUES (gen_random_uuid(),'10000000-0000-4000-8000-000000000002',?,'CAREGIVER',2)",assigned);
        jdbc.update("INSERT INTO push_subscription(id,user_id,endpoint,endpoint_hash,p256dh,auth_secret) VALUES (gen_random_uuid(),?,'https://push.example/' || ?::text,decode(repeat('00',32),'hex'),'key','secret')",assigned,assigned);
        long version=jdbc.queryForObject("SELECT version FROM group_member WHERE group_id=? AND user_id=?",Long.class,GROUP,assigned);
        HttpResponse<String> left=send("POST","/api/v1/care-groups/"+GROUP+"/memberships/me/leave","{\"expectedVersion\":"+version+"}",assignedToken,"leave");
        assertEquals(200,left.statusCode(),left.body());assertEquals("LEFT",data(left).get("membershipStatus").asText());
        assertEquals(403,send("GET","/api/v1/care-groups/"+GROUP,null,assignedToken,null).statusCode());
        assertEquals(200,send("GET","/api/v1/care-groups/10000000-0000-4000-8000-000000000002",null,assignedToken,null).statusCode());
        assertEquals(200,send("GET","/api/v1/me",null,assignedToken,null).statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM push_subscription WHERE user_id=? AND enabled",Integer.class,assigned));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM handoff_request WHERE occurrence_id=?::uuid AND reason='MEMBER_LEFT' AND status='OPEN'",Integer.class,task.get("id").asText()));
    }

    @Test void userOneTombstoneSurvivesExplicitSeriesRuleEdit()throws Exception{
        String token=login("demo-caregiver-1");LocalDate first=LocalDate.now(KST).plusDays(1);
        JsonNode created=data(send("POST","/api/v1/care-groups/"+GROUP+"/task-series",recurring("DAILY",first,first.plusDays(2),"[]","취소 보존"),token,"tombstone-create"));
        UUID firstId=UUID.fromString(created.get("occurrences").get(0).get("id").asText());UUID secondId=UUID.fromString(created.get("occurrences").get(1).get("id").asText());
        JsonNode deletePreview=data(send("POST","/api/v1/tasks/"+secondId+"/deletion-preview","{\"scope\":\"OCCURRENCE\",\"expectedVersion\":0}",token,null));
        assertEquals(200,send("DELETE","/api/v1/tasks/"+secondId,"{\"scope\":\"OCCURRENCE\",\"expectedVersion\":0,\"previewToken\":"+quote(deletePreview.get("previewToken").asText())+"}",token,"one-delete").statusCode());
        JsonNode editPreview=data(send("POST","/api/v1/tasks/"+firstId+"/series-edit-preview","{\"expectedVersion\":0,\"expectedSeriesVersion\":0,\"patch\":{\"localTime\":\"12:00\"}}",token,null));
        String edit="{\"scope\":\"SERIES_ALL_PENDING\",\"expectedVersion\":0,\"expectedSeriesVersion\":0,\"previewToken\":"+quote(editPreview.get("previewToken").asText())+",\"patch\":{\"localTime\":\"12:00\"}}";
        assertEquals(200,send("PATCH","/api/v1/tasks/"+firstId,edit,token,"tombstone-edit").statusCode());
        assertEquals("USER_ONE",jdbc.queryForObject("SELECT cancel_reason FROM task_occurrence WHERE id=?",String.class,secondId));
        assertEquals("CANCELED",jdbc.queryForObject("SELECT status FROM task_occurrence WHERE id=?",String.class,secondId));
    }

    @Test void explicitRuleEditRevivesOnlyRuleChangedAndNeverUserFuture()throws Exception{
        String token=login("demo-caregiver-1");LocalDate first=LocalDate.now(KST).plusDays(1);
        JsonNode created=data(send("POST","/api/v1/care-groups/"+GROUP+"/task-series",recurring("DAILY",first,first.plusDays(4),"[]","규칙 복구"),token,"rule-create"));
        UUID selected=UUID.fromString(created.get("occurrences").get(0).get("id").asText());UUID excluded=UUID.fromString(created.get("occurrences").get(1).get("id").asText());
        int weekday=first.getDayOfWeek().getValue();JsonNode p1=data(send("POST","/api/v1/tasks/"+selected+"/series-edit-preview","{\"expectedVersion\":0,\"expectedSeriesVersion\":0,\"patch\":{\"recurrence\":\"WEEKLY\",\"weekdays\":["+weekday+"]}}",token,null));
        assertEquals(200,send("PATCH","/api/v1/tasks/"+selected,"{\"scope\":\"SERIES_ALL_PENDING\",\"expectedVersion\":0,\"expectedSeriesVersion\":0,\"previewToken\":"+quote(p1.get("previewToken").asText())+",\"patch\":{\"recurrence\":\"WEEKLY\",\"weekdays\":["+weekday+"]}}",token,"weekly-rule").statusCode());
        assertEquals("RULE_CHANGED",jdbc.queryForObject("SELECT cancel_reason FROM task_occurrence WHERE id=?",String.class,excluded));
        long occurrenceVersion=jdbc.queryForObject("SELECT version FROM task_occurrence WHERE id=?",Long.class,selected);long seriesVersion=jdbc.queryForObject("SELECT version FROM task_series WHERE id=(SELECT series_id FROM task_occurrence WHERE id=?)",Long.class,selected);
        JsonNode p2=data(send("POST","/api/v1/tasks/"+selected+"/series-edit-preview","{\"expectedVersion\":"+occurrenceVersion+",\"expectedSeriesVersion\":"+seriesVersion+",\"patch\":{\"recurrence\":\"DAILY\",\"weekdays\":[]}}",token,null));
        assertEquals(200,send("PATCH","/api/v1/tasks/"+selected,"{\"scope\":\"SERIES_ALL_PENDING\",\"expectedVersion\":"+occurrenceVersion+",\"expectedSeriesVersion\":"+seriesVersion+",\"previewToken\":"+quote(p2.get("previewToken").asText())+",\"patch\":{\"recurrence\":\"DAILY\",\"weekdays\":[]}}",token,"daily-rule").statusCode());
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM task_occurrence WHERE id=?",String.class,excluded));assertNull(jdbc.queryForObject("SELECT cancel_reason FROM task_occurrence WHERE id=?",String.class,excluded));

        JsonNode future=data(send("POST","/api/v1/care-groups/"+GROUP+"/task-series",recurring("DAILY",first,first.plusDays(3),"[]","사용자 미래 취소"),token,"future-create"));UUID boundary=UUID.fromString(future.get("occurrences").get(1).get("id").asText());UUID keptCanceled=UUID.fromString(future.get("occurrences").get(2).get("id").asText());
        JsonNode dp=data(send("POST","/api/v1/tasks/"+boundary+"/deletion-preview","{\"scope\":\"SERIES_FROM_SELECTED\",\"expectedVersion\":0,\"expectedSeriesVersion\":0}",token,null));assertEquals(200,send("DELETE","/api/v1/tasks/"+boundary,"{\"scope\":\"SERIES_FROM_SELECTED\",\"expectedVersion\":0,\"expectedSeriesVersion\":0,\"previewToken\":"+quote(dp.get("previewToken").asText())+"}",token,"future-delete").statusCode());
        assertEquals("USER_FUTURE",jdbc.queryForObject("SELECT cancel_reason FROM task_occurrence WHERE id=?",String.class,keptCanceled));generator.scheduledGenerate();assertEquals("CANCELED",jdbc.queryForObject("SELECT status FROM task_occurrence WHERE id=?",String.class,keptCanceled));assertEquals("USER_FUTURE",jdbc.queryForObject("SELECT cancel_reason FROM task_occurrence WHERE id=?",String.class,keptCanceled));
    }

    @Test void overdueOpenHandoffExpiresAndFutureMoveCreatesNewIncident()throws Exception{
        String token=login("demo-caregiver-1");UUID handoff=UUID.randomUUID();
        jdbc.update("UPDATE task_occurrence SET starts_at=now()-interval '2 hour',ends_at=now()-interval '1 hour',assignee_user_id=NULL,assignment_origin=NULL WHERE id=?",EXISTING);
        jdbc.update("INSERT INTO handoff_request(id,group_id,occurrence_id,reason,status) VALUES (?,?,?,'NO_CANDIDATE','OPEN')",handoff,GROUP,EXISTING);
        HttpResponse<String> refused=send("POST","/api/v1/handoffs/"+handoff+"/accept","{\"expectedVersion\":0,\"expectedOccurrenceVersion\":0}",token,"expired-accept");
        assertEquals(409,refused.statusCode(),refused.body());assertEquals("EXPIRED",jdbc.queryForObject("SELECT status FROM handoff_request WHERE id=?",String.class,handoff));
        LocalDate tomorrow=LocalDate.now(KST).plusDays(1);String move="{\"scope\":\"OCCURRENCE\",\"expectedVersion\":0,\"patch\":{\"startsAt\":\""+tomorrow+"T16:00:00+09:00\",\"endsAt\":\""+tomorrow+"T16:30:00+09:00\"}}";
        assertEquals(200,send("PATCH","/api/v1/tasks/"+EXISTING,move,token,"move-expired").statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM handoff_request WHERE occurrence_id=? AND status='OPEN'",Integer.class,EXISTING));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM handoff_request WHERE occurrence_id=? AND status='EXPIRED'",Integer.class,EXISTING));
    }

    @Test void deletionFailureRollsBackTaskHandoffOutboxAndIdempotency()throws Exception{
        String token=login("demo-caregiver-1");LocalDate date=LocalDate.now(KST).plusDays(2);
        JsonNode task=data(send("POST","/api/v1/care-groups/"+GROUP+"/task-series",once(date,"18:00","롤백"),token,"rollback-create")).get("occurrences").get(0);UUID id=UUID.fromString(task.get("id").asText());
        JsonNode preview=data(send("POST","/api/v1/tasks/"+id+"/deletion-preview","{\"scope\":\"OCCURRENCE\",\"expectedVersion\":0}",token,null));
        jdbc.execute("CREATE OR REPLACE FUNCTION phase4_fail_cancel() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.event_type='TASK_CANCELED' THEN RAISE EXCEPTION 'forced rollback'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER phase4_fail_cancel_trigger BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION phase4_fail_cancel()");
        try{
            String body="{\"scope\":\"OCCURRENCE\",\"expectedVersion\":0,\"previewToken\":"+quote(preview.get("previewToken").asText())+"}";
            assertEquals(500,send("DELETE","/api/v1/tasks/"+id,body,token,"rollback-delete").statusCode());
            assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM task_occurrence WHERE id=?",String.class,id));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM notification_event WHERE occurrence_id=? AND status='CANCELED'",Integer.class,id));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE client_key='rollback-delete'",Integer.class));
        }finally{jdbc.execute("DROP TRIGGER IF EXISTS phase4_fail_cancel_trigger ON audit_event");jdbc.execute("DROP FUNCTION IF EXISTS phase4_fail_cancel()");}
    }

    @Test void leaveAndHandoffAcceptanceRaceNeverLeavesInvalidAssignment()throws Exception{
        String caregiver=login("demo-caregiver-1");LocalDate date=LocalDate.now(KST).plusDays(2);
        JsonNode task=data(send("POST","/api/v1/care-groups/"+GROUP+"/task-series",once(date,"20:30","탈퇴 수락 경쟁"),caregiver,"race-create")).get("occurrences").get(0);UUID taskId=UUID.fromString(task.get("id").asText());
        JsonNode opened=data(send("POST","/api/v1/tasks/"+taskId+"/handoffs","{\"expectedVersion\":0}",caregiver,"race-handoff"));UUID handoff=UUID.fromString(opened.get("handoff").get("id").asText());
        HttpRequest accept=request("POST","/api/v1/handoffs/"+handoff+"/accept","{\"expectedVersion\":0,\"expectedOccurrenceVersion\":1}",caregiver,"race-accept");
        HttpRequest leave=request("POST","/api/v1/care-groups/"+GROUP+"/memberships/me/leave","{\"expectedVersion\":0}",caregiver,"race-leave");
        CompletableFuture<HttpResponse<String>> accepted=client.sendAsync(accept,HttpResponse.BodyHandlers.ofString());
        CompletableFuture<HttpResponse<String>> left=client.sendAsync(leave,HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> acceptResult=accepted.join(),leaveResult=left.join();assertEquals(200,leaveResult.statusCode(),leaveResult.body());assertTrue(acceptResult.statusCode()==200||acceptResult.statusCode()==403,acceptResult.body());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM task_occurrence o JOIN group_member m ON m.group_id=o.group_id AND m.user_id=o.assignee_user_id WHERE o.id=? AND m.status<>'ACTIVE'",Integer.class,taskId));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM handoff_request WHERE occurrence_id=? AND status='OPEN'",Integer.class,taskId));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM task_occurrence o JOIN handoff_request h ON h.occurrence_id=o.id AND h.status='OPEN' WHERE o.id=? AND o.assignee_user_id IS NOT NULL",Integer.class,taskId));
    }

    private int count(UUID series){return jdbc.queryForObject("SELECT count(*) FROM task_occurrence WHERE series_id=?",Integer.class,series);}
    private UUID series(LocalDate date,String time,String title){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO task_series(id,group_id,kind,created_by,generation_key) VALUES (?,?, 'OTHER',?,?)",id,GROUP,CAREGIVER,"test:"+id);jdbc.update("INSERT INTO task_series_revision(series_id,revision_no,group_id,title,recurrence,first_date,last_date,weekdays,local_time,duration_minutes,effective_at,changed_by) VALUES (?,1,?,?,'ONCE',?,?,ARRAY[]::smallint[],?::time,30,now(),?)",id,GROUP,title,date,date,time,CAREGIVER);return id;}
    private UUID assignee(UUID series){return jdbc.queryForObject("SELECT assignee_user_id FROM task_occurrence WHERE series_id=?",UUID.class,series);}
    private String recurring(String recurrence,LocalDate first,LocalDate last,String weekdays,String title){return "{\"kind\":\"OTHER\",\"title\":"+quote(title)+",\"rule\":{\"recurrence\":\""+recurrence+"\",\"firstDate\":\""+first+"\",\"lastDate\":\""+last+"\",\"weekdays\":"+weekdays+",\"localTime\":\"10:00\",\"durationMinutes\":30},\"medicationIds\":[]}";}
    private String once(LocalDate date,String time,String title){return "{\"kind\":\"OTHER\",\"title\":"+quote(title)+",\"rule\":{\"recurrence\":\"ONCE\",\"firstDate\":\""+date+"\",\"lastDate\":\""+date+"\",\"weekdays\":[],\"localTime\":\""+time+"\",\"durationMinutes\":30},\"medicationIds\":[]}";}
    private JsonNode data(HttpResponse<String> response)throws Exception{assertTrue(response.statusCode()>=200&&response.statusCode()<300,response.body());return json.readTree(response.body()).get("data");}
    private String login(String key)throws Exception{return data(send("POST","/api/v1/auth/demo-login","{\"loginKey\":\""+key+"\"}",null,null)).get("accessToken").asText();}
    private String quote(String value){return json.writeValueAsString(value);}
    private HttpResponse<String> send(String method,String path,String body,String token,String key)throws Exception{return client.send(request(method,path,body,token,key),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));}
    private HttpRequest request(String method,String path,String body,String token,String key){HttpRequest.Builder b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/json");if(token!=null)b.header(HttpHeaders.AUTHORIZATION,"Bearer "+token);if(key!=null)b.header("Idempotency-Key",key);return b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build();}
}
