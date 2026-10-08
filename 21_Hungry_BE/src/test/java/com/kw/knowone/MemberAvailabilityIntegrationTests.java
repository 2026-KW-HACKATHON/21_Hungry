package com.kw.knowone;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql(scripts = {"classpath:phase2-fixture.sql", "classpath:phase3-fixture.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class MemberAvailabilityIntegrationTests {
    private static final UUID GROUP = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID PARENT = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID CHILD = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID LEFT = UUID.fromString("20000000-0000-4000-8000-000000000003");
    private static final UUID OTHER_GROUP_MEMBER = UUID.fromString("20000000-0000-4000-8000-000000000005");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test void activeParentAndChildrenSeeExactlyTheSameDaysAsTheOwners() throws Exception {
        String parentToken = login("01000000001");
        String childToken = login("01000000002");
        String otherChildToken = login("01000000004");
        UUID parentMember = memberId(parentToken, PARENT);
        UUID childMember = memberId(parentToken, CHILD);
        LocalDate from = LocalDate.now(KST).plusDays(1);
        LocalDate partial = from.plusDays(1);
        LocalDate unavailable = from.plusDays(2);
        LocalDate unregistered = from.plusDays(3);

        jdbc.update("UPDATE availability_day SET mode='PARTIAL' WHERE user_id=? AND local_date=?", CHILD, partial);
        jdbc.update("DELETE FROM availability_interval WHERE day_id=(SELECT id FROM availability_day WHERE user_id=? AND local_date=?)", CHILD, partial);
        jdbc.update("INSERT INTO availability_interval(id,day_id,start_minute,end_minute) SELECT gen_random_uuid(),id,600,660 FROM availability_day WHERE user_id=? AND local_date=?", CHILD, partial);
        jdbc.update("INSERT INTO availability_day(id,user_id,local_date,mode,custom_intervals) VALUES (gen_random_uuid(),?,?, 'UNAVAILABLE',false)", CHILD, unavailable);

        String range = "?fromDate=" + from + "&toDateExclusive=" + unregistered.plusDays(1);
        JsonNode childOwn = data(send("GET", "/api/v1/me/availability-days" + range, null, childToken));
        JsonNode parentSeesChild = data(send("GET", memberPath(childMember) + range, null, parentToken));
        JsonNode childSeesChild = data(send("GET", memberPath(childMember) + range, null, otherChildToken));
        assertEquals(childOwn, parentSeesChild);
        assertEquals(childOwn, childSeesChild);
        JsonNode items = parentSeesChild.get("items");
        assertEquals(4, items.size());
        assertEquals("FULL", items.get(0).get("mode").asText());
        assertEquals(420, items.get(0).get("intervals").get(0).get("startMinute").asInt());
        assertEquals(1320, items.get(0).get("intervals").get(0).get("endMinute").asInt());
        assertEquals("PARTIAL", items.get(1).get("mode").asText());
        assertEquals(600, items.get(1).get("intervals").get(0).get("startMinute").asInt());
        assertEquals(660, items.get(1).get("intervals").get(0).get("endMinute").asInt());
        assertEquals("UNAVAILABLE", items.get(2).get("mode").asText());
        assertEquals(0, items.get(2).get("intervals").size());
        assertTrue(items.get(3).get("mode").isNull());
        assertTrue(items.get(3).get("version").isNull());
        assertEquals(0, items.get(3).get("intervals").size());
        assertFalse(parentSeesChild.toString().contains("weeklyWorkPeriods"));
        assertFalse(parentSeesChild.toString().contains("occurrence"));

        JsonNode parentOwn = data(send("GET", "/api/v1/me/availability-days" + range, null, parentToken));
        JsonNode childSeesParent = data(send("GET", memberPath(parentMember) + range, null, childToken));
        assertEquals(parentOwn, childSeesParent);
        assertEquals(parentOwn, data(send("GET", memberPath(parentMember) + range, null, parentToken)));
    }

    @Test void leftPendingAndDifferentGroupMembersCannotBeRead() throws Exception {
        String childToken = login("01000000002");
        String leftToken = login("01000000003");
        String outsiderToken = login("01000000006");
        UUID activeMember = memberId(childToken, CHILD);
        String range = range();

        assertError(403, "NOT_MEMBER", send("GET", memberPath(LEFT) + range, null, childToken));
        assertError(403, "NOT_MEMBER", send("GET", memberPath(activeMember) + range, null, leftToken));
        assertError(403, "NOT_MEMBER", send("GET", memberPath(OTHER_GROUP_MEMBER) + range, null, childToken));
        assertError(403, "NOT_MEMBER", send("GET", memberPath(activeMember) + range, null, outsiderToken));

        jdbc.update("UPDATE group_member SET status='PENDING', priority=2, joined_at=NULL, left_at=NULL WHERE id=?", LEFT);
        assertError(403, "NOT_MEMBER", send("GET", memberPath(LEFT) + range, null, childToken));
        assertError(403, "NOT_MEMBER", send("GET", memberPath(activeMember) + range, null, leftToken));
        assertError(404, "NOT_FOUND", send("GET", memberPath(UUID.randomUUID()) + range, null, childToken));
        assertError(404, "NOT_FOUND", send("GET", memberPath(CHILD) + range, null, childToken));
        assertEquals(401, send("GET", memberPath(activeMember) + range, null, null).statusCode());
    }

    @Test void memberRouteIsReadOnlyAndUsesTheSameRangeValidation() throws Exception {
        String parentToken = login("01000000001");
        String childToken = login("01000000002");
        UUID childMember = memberId(parentToken, CHILD);
        LocalDate from = LocalDate.now(KST).plusDays(1);
        String path = memberPath(childMember);
        assertError(400, "VALIDATION_ERROR", send("GET", path + "?fromDate=" + from + "&toDateExclusive=" + from, null, parentToken));
        assertError(400, "VALIDATION_ERROR", send("GET", "/api/v1/me/availability-days?fromDate=" + from + "&toDateExclusive=" + from, null, childToken));
        assertError(400, "VALIDATION_ERROR", send("GET", path + "?fromDate=" + from, null, parentToken));
        assertError(400, "VALIDATION_ERROR", send("GET", "/api/v1/me/availability-days?fromDate=" + from, null, childToken));
        assertEquals(405, send("PUT", path, "{}", parentToken).statusCode());
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM availability_day WHERE user_id=? AND local_date=?", Long.class, CHILD, from));
    }

    private UUID memberId(String token, UUID userId) throws Exception {
        JsonNode members = data(send("GET", "/api/v1/care-groups/" + GROUP + "/members", null, token)).get("items");
        for (JsonNode member : members)
            if (userId.toString().equals(member.get("user").get("id").asText()))
                return UUID.fromString(member.get("id").asText());
        throw new AssertionError("Member missing from G05: " + userId);
    }

    private String memberPath(UUID memberId) {
        return "/api/v1/care-groups/" + GROUP + "/members/" + memberId + "/availability-days";
    }

    private String range() {
        LocalDate from = LocalDate.now(KST).plusDays(1);
        return "?fromDate=" + from + "&toDateExclusive=" + from.plusDays(2);
    }

    private String login(String phone) throws Exception {
        return data(send("POST", "/api/v1/auth/login", "{\"phoneNumber\":\"" + phone + "\"}", null))
                .get("accessToken").asText();
    }

    private JsonNode data(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body()).get("data");
    }

    private void assertError(int status, String code, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, json.readTree(response.body()).get("error").get("code").asText());
    }

    private HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}