package com.kw.knowone;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql(scripts={"classpath:phase2-fixture.sql","classpath:phase3-fixture.sql"},executionPhase=Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ApiV12IntegrationTests {
    private static final UUID GROUP=UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    @Value("${local.server.port}")int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    final HttpClient client=HttpClient.newHttpClient();

    @Test void signupLoginAndOnboardingFollowPhoneContract()throws Exception{
        HttpResponse<String> parent=send("POST","/api/v1/auth/signup","{\"phoneNumber\":\"010-9999-0001\",\"accountRole\":\"PARENT\"}",null,null);
        assertEquals(201,parent.statusCode(),parent.body());JsonNode p=data(parent);assertTrue(p.get("user").get("displayName").isNull());assertNotNull(p.get("groupId"));
        assertEquals(409,send("POST","/api/v1/auth/signup","{\"phoneNumber\":\"01099990001\",\"accountRole\":\"PARENT\"}",null,null).statusCode());
        assertEquals(400,send("POST","/api/v1/auth/signup","{\"phoneNumber\":\"01099990002\",\"accountRole\":\"CHILD\"}",null,null).statusCode());
        String token=data(send("POST","/api/v1/auth/login","{\"phoneNumber\":\"01099990001\"}",null,null)).get("accessToken").asText();
        assertEquals("PARENT_PROFILE_PENDING",data(send("GET","/api/v1/me",null,token,null)).get("onboardingState").asText());
    }

    @Test void firstChildJoinCompletesParentProfileWithoutTriggerFailure()throws Exception{
        JsonNode parent=data(send("POST","/api/v1/auth/signup",
                "{\"phoneNumber\":\"010-9999-0011\",\"accountRole\":\"PARENT\"}",null,null));
        UUID parentId=UUID.fromString(parent.get("user").get("id").asText());
        UUID groupId=UUID.fromString(parent.get("groupId").asText());
        assertEquals(201,send("POST","/api/v1/auth/signup",
                "{\"phoneNumber\":\"010-9999-0012\",\"accountRole\":\"CHILD\",\"displayName\":\"테스트 자녀\"}",null,null).statusCode());
        String childToken=data(send("POST","/api/v1/auth/login",
                "{\"phoneNumber\":\"01099990012\"}",null,null)).get("accessToken").asText();

        String body="{\"recipientUserId\":\""+parentId+"\",\"parentProfile\":{"+
                "\"relation\":\"MOTHER\",\"name\":\"테스트 부모\",\"birthYear\":1960}}";
        HttpResponse<String> response=send("POST","/api/v1/care-groups/"+groupId+"/join",body,childToken,"first-child-join");

        assertEquals(201,response.statusCode(),response.body());
        JsonNode joined=data(response);
        assertEquals("ACTIVE",joined.get("membership").get("status").asText());
        assertEquals("READY",joined.get("nextAction").asText());
        assertEquals("테스트 부모",jdbc.queryForObject("SELECT display_name FROM app_user WHERE id=?",String.class,parentId));
        assertNotNull(jdbc.queryForObject("SELECT parent_profile_completed_at FROM care_group WHERE id=?",java.sql.Timestamp.class,groupId));
    }

    @Test void manualOutsideHorizonCreatesOneTaskWithoutImmediateNoCandidatePushAndCalendarIsComplete()throws Exception{
        String token=login("01000000002");LocalDate date=LocalDate.now(KST).plusDays(20);
        String body="{\"kind\":\"OTHER\",\"title\":\"원거리 단건\",\"rule\":{\"recurrence\":\"ONCE\",\"firstDate\":\""+date+"\",\"lastDate\":\""+date+"\",\"weekdays\":[],\"localTime\":\"10:00\",\"durationMinutes\":30}}";
        JsonNode created=data(send("POST","/api/v1/care-groups/"+GROUP+"/task-series",body,token,"v12-manual"));
        assertEquals(1,created.get("occurrences").size());assertFalse(created.has("generationWindow"));
        UUID taskId=UUID.fromString(created.get("occurrences").get(0).get("id").asText());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM handoff_request WHERE occurrence_id=? AND reason='NO_CANDIDATE'",Integer.class,taskId));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM notification_event WHERE occurrence_id=? AND event_type='HANDOFF_OPEN'",Integer.class,taskId));
        assertEquals(taskId.toString(),data(send("GET","/api/v1/care-groups/"+GROUP+"/tasks?date="+date,null,token,null)).get("items").get(0).get("id").asText());
        JsonNode calendar=data(send("GET","/api/v1/care-groups/"+GROUP+"/calendar?month="+YearMonth.from(date),null,token,null));
        assertEquals(date.lengthOfMonth(),calendar.get("days").size());
        assertEquals("ATTENTION",calendar.get("days").get(date.getDayOfMonth()-1).get("indicator").asText());
    }

    @Test void pendingJoinDecisionUsesV12RequestWrapper()throws Exception{
        String applicant=login("01000000003");
        JsonNode joined=data(send("POST","/api/v1/care-groups/"+GROUP+"/join","{\"recipientUserId\":\"00000000-0000-4000-8000-000000000001\"}",applicant,"v12-join"));
        assertEquals("PENDING",joined.get("membership").get("status").asText());UUID requestId=UUID.fromString(joined.get("request").get("id").asText());
        String primary=login("01000000002");JsonNode pending=data(send("GET","/api/v1/care-groups/"+GROUP+"/join-requests",null,primary,null));
        assertEquals(requestId.toString(),pending.get("items").get(0).get("id").asText());
        JsonNode decided=data(send("POST","/api/v1/care-groups/"+GROUP+"/join-requests/"+requestId+"/decision","{\"expectedVersion\":0,\"decision\":\"APPROVE\"}",primary,"v12-approve"));
        assertEquals("APPROVED",decided.get("request").get("status").asText());
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM group_member WHERE group_id=? AND user_id='00000000-0000-4000-8000-000000000003'",String.class,GROUP));
    }

    private String login(String phone)throws Exception{return data(send("POST","/api/v1/auth/login","{\"phoneNumber\":\""+phone+"\"}",null,null)).get("accessToken").asText();}
    private JsonNode data(HttpResponse<String> response)throws Exception{assertTrue(response.statusCode()>=200&&response.statusCode()<300,response.body());return json.readTree(response.body()).get("data");}
    private HttpResponse<String> send(String method,String path,String body,String token,String key)throws Exception{HttpRequest.Builder b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/json");if(token!=null)b.header(HttpHeaders.AUTHORIZATION,"Bearer "+token);if(key!=null)b.header("Idempotency-Key",key);return client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));}
}
