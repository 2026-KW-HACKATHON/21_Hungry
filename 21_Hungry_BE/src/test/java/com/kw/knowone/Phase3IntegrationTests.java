package com.kw.knowone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import com.kw.knowone.availability.entity.AvailabilityModels.MinuteInterval;
import com.kw.knowone.availability.entity.AvailabilityModels.WorkPeriod;
import com.kw.knowone.availability.service.AvailabilityCalculator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql(scripts={"classpath:phase2-fixture.sql","classpath:phase3-fixture.sql"},executionPhase=Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class Phase3IntegrationTests {
    private static final UUID GROUP=UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID CAREGIVER=UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID SECOND=UUID.fromString("00000000-0000-4000-8000-000000000004");
    private static final UUID RECIPIENT=UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID EXISTING=UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    @Value("${local.server.port}")int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AvailabilityCalculator calculator;
    final HttpClient client=HttpClient.newHttpClient();

    @Test void intervalRulesMergeSplitSubtractAndTreatMissingAsUnavailable(){
        assertEquals(List.of(new MinuteInterval(10,40)),calculator.normalizeIntervals(List.of(
                new MinuteInterval(20,40),new MinuteInterval(10,20)),0,1440,false));
        List<WorkPeriod> split=calculator.normalizeWork(List.of(new WorkPeriod(1,1320,120)));
        assertEquals(List.of(new WorkPeriod(1,1320,1440),new WorkPeriod(2,0,120)),split);
        assertEquals(List.of(new MinuteInterval(420,540),new MinuteInterval(1080,1320)),
                calculator.calculate("PARTIAL",false,List.of(),LocalDate.of(2026,10,5),420,1320,
                        List.of(new WorkPeriod(1,540,1080)),false));
        assertFalse(calculator.covers(date->List.of(),java.time.Instant.now(),java.time.Instant.now().plusSeconds(60),KST));
        LocalDate midnightDate=LocalDate.of(2026,10,6);
        assertTrue(calculator.covers(date->date.equals(midnightDate)?List.of(new MinuteInterval(1380,1440)):
                List.of(new MinuteInterval(0,60)),midnightDate.atTime(23,0).atZone(KST).toInstant(),
                midnightDate.plusDays(1).atTime(0,30).atZone(KST).toInstant(),KST));
    }

    @Test void daysPreviewSaveMergesIntervalsAndRejectsNullVersionRace()throws Exception{
        String token=login("demo-caregiver-1");LocalDate date=LocalDate.now(KST).plusDays(4);
        String body="{\"fromDate\":\""+date+"\",\"toDateExclusive\":\""+date.plusDays(1)+"\",\"mode\":\"PARTIAL\",\"customIntervals\":true,\"intervals\":[{\"startMinute\":600,\"endMinute\":660},{\"startMinute\":660,\"endMinute\":720}],\"expectedDays\":[{\"date\":\""+date+"\",\"version\":null}]}";
        HttpResponse<String> preview=send("POST","/api/v1/me/availability-days/preview",body,token,null);assertEquals(200,preview.statusCode(),preview.body());
        JsonNode data=json.readTree(preview.body()).get("data");assertEquals(1,data.get("days").get(0).get("intervals").size());
        String save=body.substring(0,body.length()-1)+",\"previewToken\":"+json.writeValueAsString(data.get("previewToken").asText())+"}";
        HttpResponse<String> saved=send("PUT","/api/v1/me/availability-days",save,token,"days-1");assertEquals(200,saved.statusCode(),saved.body());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM availability_interval i JOIN availability_day d ON d.id=i.day_id WHERE d.user_id=? AND d.local_date=?",Integer.class,CAREGIVER,date));

        LocalDate race=date.plusDays(1);String raceBody="{\"fromDate\":\""+race+"\",\"toDateExclusive\":\""+race.plusDays(1)+"\",\"mode\":\"PARTIAL\",\"customIntervals\":true,\"intervals\":[{\"startMinute\":600,\"endMinute\":720}],\"expectedDays\":[{\"date\":\""+race+"\",\"version\":null}]}";
        HttpResponse<String> racePreview=send("POST","/api/v1/me/availability-days/preview",raceBody,token,null);assertEquals(200,racePreview.statusCode());
        jdbc.update("INSERT INTO availability_day(id,user_id,local_date,mode,custom_intervals) VALUES (?,?,?,?,false)",UUID.randomUUID(),CAREGIVER,race,"UNAVAILABLE");
        JsonNode raceData=json.readTree(racePreview.body()).get("data");String raceSave=raceBody.substring(0,raceBody.length()-1)+",\"previewToken\":"+json.writeValueAsString(raceData.get("previewToken").asText())+"}";
        assertEquals(409,send("PUT","/api/v1/me/availability-days",raceSave,token,"days-race").statusCode());
    }

    @Test void configPreviewDoesNotMutateAndSaveReleasesOnlyFutureAssignment()throws Exception{
        String token=login("demo-caregiver-1");JsonNode config=json.readTree(send("GET","/api/v1/me/availability-config",null,token,null).body()).get("data");
        String base="{\"expectedUserVersion\":"+config.get("userVersion").asLong()+",\"expectedWorkConfigVersion\":"+json.writeValueAsString(config.get("workConfigVersion").asText())+",\"patch\":{\"activeEndMinute\":480}}";
        HttpResponse<String> preview=send("POST","/api/v1/me/availability-config/preview",base,token,null);assertEquals(200,preview.statusCode(),preview.body());
        assertEquals(CAREGIVER,jdbc.queryForObject("SELECT assignee_user_id FROM task_occurrence WHERE id=?",UUID.class,EXISTING));
        String previewToken=json.readTree(preview.body()).get("data").get("previewToken").asText();
        String tampered=(previewToken.charAt(0)=='A'?'B':'A')+previewToken.substring(1);
        String bad=base.substring(0,base.length()-1)+",\"previewToken\":"+json.writeValueAsString(tampered)+"}";
        assertEquals(409,send("PATCH","/api/v1/me/availability-config",bad,token,"config-tampered").statusCode());
        String save=base.substring(0,base.length()-1)+",\"previewToken\":"+json.writeValueAsString(previewToken)+"}";
        HttpResponse<String> result=send("PATCH","/api/v1/me/availability-config",save,token,"config-1");assertEquals(200,result.statusCode(),result.body());
        assertNull(jdbc.queryForObject("SELECT assignee_user_id FROM task_occurrence WHERE id=?",UUID.class,EXISTING));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM handoff_request WHERE occurrence_id=? AND reason='AVAILABILITY' AND status='OPEN'",Integer.class,EXISTING));
        assertEquals(result.body(),send("PATCH","/api/v1/me/availability-config",save,token,"config-1").body());
    }

    @Test void onceCreationAssignmentCompletionReopenAndHistoryFollowContract()throws Exception{
        String token=login("demo-caregiver-1");LocalDate date=LocalDate.now(KST).plusDays(2);
        String create="{\"kind\":\"OTHER\",\"title\":\"테스트 일정\",\"description\":null,\"rule\":{\"recurrence\":\"ONCE\",\"firstDate\":\""+date+"\",\"lastDate\":\""+date+"\",\"weekdays\":[],\"localTime\":\"10:00\",\"durationMinutes\":30},\"medicationIds\":[]}";
        HttpResponse<String> created=send("POST","/api/v1/care-groups/"+GROUP+"/task-series",create,token,"create-1");assertEquals(201,created.statusCode(),created.body());
        JsonNode task=json.readTree(created.body()).get("data").get("occurrences").get(0);UUID id=UUID.fromString(task.get("id").asText());
        assertEquals(SECOND.toString(),task.get("assignee").get("id").asText());
        assertEquals(created.body(),send("POST","/api/v1/care-groups/"+GROUP+"/task-series",create,token,"create-1").body());
        assertEquals(200,send("GET","/api/v1/tasks/"+id,null,token,null).statusCode());
        assertEquals(200,send("GET","/api/v1/care-groups/"+GROUP+"/tasks?from="+date+"T00:00:00Z&to="+date.plusDays(2)+"T00:00:00Z",null,token,null).statusCode());

        String complete="{\"expectedVersion\":0,\"performedByUserId\":\""+RECIPIENT+"\"}";
        HttpResponse<String> completed=send("POST","/api/v1/tasks/"+id+"/complete",complete,token,"complete-1");assertEquals(200,completed.statusCode(),completed.body());
        assertEquals("COMPLETED",json.readTree(completed.body()).get("data").get("occurrence").get("executionStatus").asText());
        HttpResponse<String> reopened=send("POST","/api/v1/tasks/"+id+"/reopen","{\"expectedVersion\":1}",token,"reopen-1");assertEquals(200,reopened.statusCode(),reopened.body());
        assertEquals("PENDING",json.readTree(reopened.body()).get("data").get("occurrence").get("executionStatus").asText());
        JsonNode history=json.readTree(send("GET","/api/v1/tasks/"+id+"/history",null,token,null).body()).get("data");assertTrue(history.get("items").size()>=3);
    }

    @Test void noCandidateIsSuccessAndManualRecipientAssignmentChecksAvailability()throws Exception{
        String token=login("demo-caregiver-1");LocalDate date=LocalDate.now(KST).plusDays(3);
        String create="{\"kind\":\"OTHER\",\"title\":\"미배정\",\"rule\":{\"recurrence\":\"ONCE\",\"firstDate\":\""+date+"\",\"lastDate\":\""+date+"\",\"weekdays\":[],\"localTime\":\"10:00\",\"durationMinutes\":30},\"medicationIds\":[]}";
        JsonNode task=json.readTree(send("POST","/api/v1/care-groups/"+GROUP+"/task-series",create,token,"no-candidate").body()).get("data").get("occurrences").get(0);
        assertTrue(task.get("assignee").isNull());assertEquals("NO_CANDIDATE",task.get("openHandoff").get("reason").asText());UUID id=UUID.fromString(task.get("id").asText());
        String assignment="{\"expectedVersion\":0,\"assigneeUserId\":\""+RECIPIENT+"\"}";
        HttpResponse<String> assigned=send("PUT","/api/v1/tasks/"+id+"/assignment",assignment,token,"assign-recipient");assertEquals(200,assigned.statusCode(),assigned.body());
        assertEquals("MANUAL",json.readTree(assigned.body()).get("data").get("occurrence").get("assignmentOrigin").asText());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM handoff_request WHERE occurrence_id=? AND status='ACCEPTED'",Integer.class,id));
    }

    @Test void concurrentOverlappingCreationsNeverAssignSameCaregiver()throws Exception{
        String token=login("demo-caregiver-1");LocalDate date=LocalDate.now(KST).plusDays(2);
        String create="{\"kind\":\"OTHER\",\"title\":\"동시 생성\",\"rule\":{\"recurrence\":\"ONCE\",\"firstDate\":\""+date+"\",\"lastDate\":\""+date+"\",\"weekdays\":[],\"localTime\":\"11:00\",\"durationMinutes\":30},\"medicationIds\":[]}";
        HttpRequest first=request("POST","/api/v1/care-groups/"+GROUP+"/task-series",create,token,"concurrent-create-a");
        HttpRequest second=request("POST","/api/v1/care-groups/"+GROUP+"/task-series",create,token,"concurrent-create-b");
        CompletableFuture<HttpResponse<String>> firstFuture=client.sendAsync(first,HttpResponse.BodyHandlers.ofString());
        CompletableFuture<HttpResponse<String>> secondFuture=client.sendAsync(second,HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> a=firstFuture.join();HttpResponse<String> b=secondFuture.join();
        assertEquals(201,a.statusCode(),a.body());assertEquals(201,b.statusCode(),b.body());
        String aUser=json.readTree(a.body()).get("data").get("occurrences").get(0).get("assignee").get("id").asText();
        String bUser=json.readTree(b.body()).get("data").get("occurrences").get(0).get("assignee").get("id").asText();
        assertFalse(aUser.equals(bUser));
    }

    @Test void conflictsAcrossGroupsExcludeCandidateButTouchingBoundaryDoesNot()throws Exception{
        UUID group2=UUID.fromString("10000000-0000-4000-8000-000000000002");
        jdbc.update("INSERT INTO group_member(id,group_id,user_id,role,priority) VALUES (?,?,?,?,?)",
                UUID.randomUUID(),group2,SECOND,"CAREGIVER",1);
        String secondToken=login("demo-caregiver-3");String firstToken=login("demo-caregiver-1");
        LocalDate date=LocalDate.now(KST).plusDays(2);
        String group2Task=createBody(date,"12:00",30,"다른 공동체");
        JsonNode occupied=json.readTree(send("POST","/api/v1/care-groups/"+group2+"/task-series",group2Task,secondToken,"cross-source").body()).get("data").get("occurrences").get(0);
        assertEquals(SECOND.toString(),occupied.get("assignee").get("id").asText());

        JsonNode overlapping=json.readTree(send("POST","/api/v1/care-groups/"+GROUP+"/task-series",
                createBody(date,"12:15",15,"충돌"),firstToken,"cross-overlap").body()).get("data").get("occurrences").get(0);
        assertEquals(CAREGIVER.toString(),overlapping.get("assignee").get("id").asText());
        JsonNode touching=json.readTree(send("POST","/api/v1/care-groups/"+GROUP+"/task-series",
                createBody(date,"12:30",15,"경계"),firstToken,"cross-touch").body()).get("data").get("occurrences").get(0);
        assertEquals(SECOND.toString(),touching.get("assignee").get("id").asText());
    }

    @Test void outsideHorizonKeepsSeriesAndUnsupportedContractsAreRejected()throws Exception{
        String token=login("demo-caregiver-1");LocalDate date=LocalDate.now(KST).plusDays(20);
        HttpResponse<String> outside=send("POST","/api/v1/care-groups/"+GROUP+"/task-series",
                createBody(date,"10:00",30,"장기 단발"),token,"outside-horizon");
        assertEquals(201,outside.statusCode(),outside.body());JsonNode data=json.readTree(outside.body()).get("data");
        assertEquals(0,data.get("occurrences").size());
        assertEquals(200,send("GET","/api/v1/task-series/"+data.get("seriesId").asText(),null,token,null).statusCode());
        String recurring=createBody(date,"10:00",30,"반복").replace("\"ONCE\"","\"DAILY\"");
        assertEquals(400,send("POST","/api/v1/care-groups/"+GROUP+"/task-series",recurring,token,"unsupported-repeat").statusCode());
        String medication=createBody(date,"10:00",30,"복약").replace("\"OTHER\"","\"MEDICATION\"");
        assertEquals(400,send("POST","/api/v1/care-groups/"+GROUP+"/task-series",medication,token,"unsupported-med").statusCode());
    }

    @Test void multiDaySaveRollsBackEveryDayOnDatabaseFailure()throws Exception{
        String token=login("demo-caregiver-1");LocalDate from=LocalDate.now(KST).plusDays(5);LocalDate failDate=from.plusDays(1);
        String core="{\"fromDate\":\""+from+"\",\"toDateExclusive\":\""+from.plusDays(2)+"\",\"mode\":\"UNAVAILABLE\",\"customIntervals\":false,\"intervals\":[],\"expectedDays\":[{\"date\":\""+from+"\",\"version\":null},{\"date\":\""+failDate+"\",\"version\":null}]}";
        JsonNode preview=json.readTree(send("POST","/api/v1/me/availability-days/preview",core,token,null).body()).get("data");
        jdbc.execute("CREATE OR REPLACE FUNCTION phase3_fail_day() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.local_date = DATE '"+failDate+"' THEN RAISE EXCEPTION 'forced rollback'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER phase3_fail_day_trigger BEFORE INSERT ON availability_day FOR EACH ROW EXECUTE FUNCTION phase3_fail_day()");
        try{
            String save=core.substring(0,core.length()-1)+",\"previewToken\":"+json.writeValueAsString(preview.get("previewToken").asText())+"}";
            assertEquals(500,send("PUT","/api/v1/me/availability-days",save,token,"rollback-days").statusCode());
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM availability_day WHERE user_id=? AND local_date>=? AND local_date<?",Integer.class,CAREGIVER,from,from.plusDays(2)));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE client_key='rollback-days'",Integer.class));
        }finally{
            jdbc.execute("DROP TRIGGER IF EXISTS phase3_fail_day_trigger ON availability_day");
            jdbc.execute("DROP FUNCTION IF EXISTS phase3_fail_day()");
        }
    }

    private String login(String key)throws Exception{JsonNode data=json.readTree(send("POST","/api/v1/auth/demo-login","{\"loginKey\":\""+key+"\"}",null,null).body()).get("data");return data.get("accessToken").asText();}
    private String createBody(LocalDate date,String time,int duration,String title){return "{\"kind\":\"OTHER\",\"title\":"+quote(title)+",\"rule\":{\"recurrence\":\"ONCE\",\"firstDate\":\""+date+"\",\"lastDate\":\""+date+"\",\"weekdays\":[],\"localTime\":\""+time+"\",\"durationMinutes\":"+duration+"},\"medicationIds\":[]}";}
    private String quote(String value){try{return json.writeValueAsString(value);}catch(RuntimeException e){throw e;}}
    private HttpResponse<String> send(String method,String path,String body,String token,String key)throws Exception{return client.send(request(method,path,body,token,key),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));}
    private HttpRequest request(String method,String path,String body,String token,String key){HttpRequest.Builder b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/json");if(token!=null)b.header(HttpHeaders.AUTHORIZATION,"Bearer "+token);if(key!=null)b.header("Idempotency-Key",key);return b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build();}
}
