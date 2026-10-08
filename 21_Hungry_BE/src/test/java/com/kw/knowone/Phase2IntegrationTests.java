package com.kw.knowone;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import com.kw.knowone.auth.service.AuthService;
import com.kw.knowone.common.preview.PreviewTokenService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql(scripts = "classpath:phase2-fixture.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class Phase2IntegrationTests {
    private static final UUID GROUP_1 = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID GROUP_2 = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID USER_1 = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID MEMBER_1 = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID OTHER_MEMBER = UUID.fromString("20000000-0000-4000-8000-000000000006");

    @Value("${local.server.port}")
    private int port;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PreviewTokenService previewTokenService;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void retiredDemoEndpointsReturn410AndPhoneLoginsStoreOnlyTokenHashes() throws Exception {
        HttpResponse<String> accounts = get("/api/v1/auth/demo-accounts", null);
        assertEquals(410, accounts.statusCode());
        assertTrue(accounts.body().contains("ENDPOINT_RETIRED"));
        assertEquals(410,post("/api/v1/auth/demo-login","{\"loginKey\":\"demo-caregiver-1\"}",null,null).statusCode());

        for (String key : List.of("demo-recipient", "demo-caregiver-1", "demo-caregiver-2", "demo-caregiver-3")) {
            Login login = login(key);
            assertEquals(43, login.token().length());
            assertEquals(32, Base64.getUrlDecoder().decode(login.token()).length);
            byte[] stored = jdbcTemplate.queryForObject("""
                    SELECT token_hash FROM auth_session WHERE user_id = ? ORDER BY created_at DESC LIMIT 1
                    """, byte[].class, login.userId());
            assertArrayEquals(AuthService.sha256(login.token()), stored);
        }
    }

    @Test
    void logoutRevokesOnlyCurrentSession() throws Exception {
        Login first = login("demo-caregiver-1");
        Login second = login("demo-caregiver-1");
        assertNotEquals(first.token(), second.token());
        assertEquals(204, post("/api/v1/auth/logout", "", first.token(), null).statusCode());
        assertUnauthorized(get("/api/v1/me", first.token()));
        assertEquals(200, get("/api/v1/me", second.token()).statusCode());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM auth_session WHERE user_id = ? AND revoked_at IS NOT NULL", Integer.class, USER_1));
    }

    @Test
    void missingTamperedExpiredRevokedAndInactiveTokensReturn401() throws Exception {
        assertUnauthorized(get("/api/v1/me", null));
        Login tampered = login("demo-caregiver-1");
        String tamperedToken = (tampered.token().charAt(0) == 'A' ? "B" : "A") + tampered.token().substring(1);
        assertUnauthorized(get("/api/v1/me", tamperedToken));

        Login expired = login("demo-caregiver-1");
        jdbcTemplate.update("""
                UPDATE auth_session SET created_at = now() - interval '2 days', expires_at = now() - interval '1 day'
                WHERE token_hash = ?
                """, AuthService.sha256(expired.token()));
        assertUnauthorized(get("/api/v1/me", expired.token()));

        Login revoked = login("demo-caregiver-1");
        jdbcTemplate.update("UPDATE auth_session SET revoked_at = now() WHERE token_hash = ?",
                AuthService.sha256(revoked.token()));
        assertUnauthorized(get("/api/v1/me", revoked.token()));

        Login inactive = login("demo-caregiver-1");
        jdbcTemplate.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", USER_1);
        assertUnauthorized(get("/api/v1/me", inactive.token()));
    }

    @Test
    void groupReadsUseActualGroupAndActiveMembership() throws Exception {
        String active = login("demo-caregiver-1").token();
        HttpResponse<String> list = get("/api/v1/me/care-groups", active);
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains(GROUP_1.toString()));
        assertEquals(200, get("/api/v1/care-groups/" + GROUP_1, active).statusCode());
        assertForbidden(get("/api/v1/care-groups/" + GROUP_2, active), "NOT_MEMBER");

        String left = login("demo-caregiver-2").token();
        assertForbidden(get("/api/v1/care-groups/" + GROUP_1 + "/members", left), "NOT_MEMBER");
        HttpResponse<String> members = get("/api/v1/care-groups/" + GROUP_1 + "/members", active);
        assertEquals(200, members.statusCode());
        assertTrue(members.body().contains("돌봄 대상"));
        assertTrue(!members.body().contains("보호자 2"));
    }

    @Test
    void lookupJoinDuplicateAndRejoinFollowContractAndWriteEvents() throws Exception {
        String caregiver3 = login("demo-caregiver-3").token();
        HttpResponse<String> lookup = post("/api/v1/care-groups/recipient-lookup",
                "{\"phoneNumber\":\"010-0000-0001\"}", caregiver3, null);
        assertEquals(200, lookup.statusCode());
        assertTrue(lookup.body().contains(GROUP_1.toString()));

        String joinBody = "{\"recipientUserId\":\"00000000-0000-4000-8000-000000000001\"}";
        HttpResponse<String> missingKey = post("/api/v1/care-groups/" + GROUP_1 + "/join", joinBody,
                caregiver3, null);
        assertEquals(400, missingKey.statusCode());
        HttpResponse<String> joined = post("/api/v1/care-groups/" + GROUP_1 + "/join", joinBody,
                caregiver3, "join-new");
        assertEquals(202, joined.statusCode(),joined.body());
        assertEquals("PENDING",objectMapper.readTree(joined.body()).get("data").get("membership").get("status").asText());
        HttpResponse<String> joinReplay = post("/api/v1/care-groups/" + GROUP_1 + "/join", joinBody,
                caregiver3, "join-new");
        assertEquals(joined.body(), joinReplay.body());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM audit_event", Integer.class));
    }

    @Test
    void priorityUpdateValidatesAuthorityMembershipVersionAndInput() throws Exception {
        jdbcTemplate.update("INSERT INTO group_member(id,group_id,user_id,role,priority) VALUES (gen_random_uuid(),?,?,'CAREGIVER',1)",GROUP_1,UUID.fromString("00000000-0000-4000-8000-000000000004"));
        String token = login("demo-caregiver-1").token();
        String body = priorityBody(MEMBER_1, 2, 0);
        HttpResponse<String> missingKey = put("/api/v1/care-groups/" + GROUP_1 + "/member-priorities",
                body, token, null);
        assertEquals(400, missingKey.statusCode());
        assertTrue(missingKey.body().contains("IDEMPOTENCY_KEY_REQUIRED"));
        HttpResponse<String> success = put("/api/v1/care-groups/" + GROUP_1 + "/member-priorities",
                body, token, "priority-ok");
        assertEquals(200, success.statusCode());
        assertEquals(1, objectMapper.readTree(success.body()).get("data").get("items").get(0).get("version").asInt());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM audit_event", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM notification_event", Integer.class));
        assertEquals(USER_1, jdbcTemplate.queryForObject(
                "SELECT assignee_user_id FROM task_occurrence WHERE id = '40000000-0000-4000-8000-000000000001'",
                UUID.class));

        HttpResponse<String> conflict = put("/api/v1/care-groups/" + GROUP_1 + "/member-priorities",
                priorityBody(MEMBER_1, 1, 0), token, "priority-version");
        assertEquals(409, conflict.statusCode());
        assertTrue(conflict.body().contains("VERSION_CONFLICT"));

        String partlyInvalid = "{\"items\":[{\"memberId\":\"" + MEMBER_1
                + "\",\"priority\":1,\"expectedVersion\":1},{\"memberId\":\"" + OTHER_MEMBER
                + "\",\"priority\":2,\"expectedVersion\":0}]}";
        HttpResponse<String> crossGroup = put("/api/v1/care-groups/" + GROUP_1 + "/member-priorities",
                partlyInvalid, token, "priority-cross");
        assertForbidden(crossGroup, "NOT_MEMBER");
        assertEquals(2, jdbcTemplate.queryForObject("SELECT priority FROM group_member WHERE id = ?", Integer.class,
                MEMBER_1));
        assertEquals(1L, currentVersion(MEMBER_1));

        String duplicate = "{\"items\":[{\"memberId\":\"" + MEMBER_1 + "\",\"priority\":2,\"expectedVersion\":1},"
                + "{\"memberId\":\"" + MEMBER_1 + "\",\"priority\":1,\"expectedVersion\":1}]}";
        HttpResponse<String> duplicateResponse = put("/api/v1/care-groups/" + GROUP_1 + "/member-priorities",
                duplicate, token, "priority-duplicate");
        assertEquals(400, duplicateResponse.statusCode());
        assertTrue(duplicateResponse.body().contains("VALIDATION_ERROR"));
    }

    @Test
    void idempotencyReplaysSuccessAndRejectsDifferentBody() throws Exception {
        String token = login("demo-caregiver-1").token();
        String path = "/api/v1/care-groups/" + GROUP_1 + "/member-priorities";
        String body = priorityBody(MEMBER_1, 1, 0);
        HttpResponse<String> first = put(path, body, token, "same-key");
        HttpResponse<String> replay = put(path, body, token, "same-key");
        assertEquals(200, first.statusCode());
        assertEquals(first.body(), replay.body());
        assertEquals(1L, currentVersion(MEMBER_1));
        HttpResponse<String> reused = put(path, priorityBody(MEMBER_1, 2, 1), token, "same-key");
        assertEquals(409, reused.statusCode());
        assertTrue(reused.body().contains("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void concurrentIdenticalRequestsMutateOnce() throws Exception {
        String token = login("demo-caregiver-1").token();
        String path = "/api/v1/care-groups/" + GROUP_1 + "/member-priorities";
        HttpRequest request = request(path, "PUT", priorityBody(MEMBER_1, 1, 0), token, "concurrent-key");
        CompletableFuture<HttpResponse<String>> first = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        CompletableFuture<HttpResponse<String>> second = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> firstResponse = first.join();
        HttpResponse<String> secondResponse = second.join();
        assertEquals(200, firstResponse.statusCode());
        assertEquals(firstResponse.body(), secondResponse.body());
        assertEquals(1L, currentVersion(MEMBER_1));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM idempotency_record", Integer.class));
    }

    @Test
    void authorizationIsRecheckedBeforeReplay() throws Exception {
        String token = login("demo-caregiver-1").token();
        String path = "/api/v1/care-groups/" + GROUP_1 + "/member-priorities";
        String body = priorityBody(MEMBER_1, 1, 0);
        assertEquals(200, put(path, body, token, "permission-key").statusCode());
        jdbcTemplate.update("UPDATE group_member SET status = 'LEFT', left_at = now(), version = version + 1 WHERE id = ?",
                MEMBER_1);
        assertForbidden(put(path, body, token, "permission-key"), "NOT_MEMBER");
        assertEquals(1, jdbcTemplate.queryForObject("SELECT priority FROM group_member WHERE id = ?", Integer.class, MEMBER_1));
    }

    @Test
    void databaseConstraintsAndPreviewVerificationAreReal() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
                INSERT INTO group_member(id, group_id, user_id, role, priority)
                VALUES (?, ?, ?, 'RECIPIENT', 1)
                """, UUID.randomUUID(), GROUP_1, UUID.fromString("00000000-0000-4000-8000-000000000004")));

        String token = previewTokenService.issue(USER_1, "TEST_OPERATION", "payload-v1", "state-v1");
        assertEquals(USER_1, previewTokenService.verify(token, USER_1, "TEST_OPERATION", "payload-v1").userId());
        assertThrows(RuntimeException.class,
                () -> previewTokenService.verify(token, USER_1, "TEST_OPERATION", "payload-v2"));
        assertThrows(RuntimeException.class,
                () -> previewTokenService.verify((token.charAt(0) == 'A' ? "B" : "A") + token.substring(1),
                        USER_1, "TEST_OPERATION", "payload-v1"));
    }

    private Login login(String loginKey) throws Exception {
        HttpResponse<String> response = post("/api/v1/auth/login",
                "{\"phoneNumber\":\"" + phone(loginKey) + "\"}", null, null);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode data = objectMapper.readTree(response.body()).get("data");
        return new Login(data.get("accessToken").asText(), UUID.fromString(data.get("user").get("id").asText()));
    }

    private String phone(String key){return switch(key){case "demo-recipient"->"01000000001";case "demo-caregiver-1"->"01000000002";case "demo-caregiver-2"->"01000000003";case "demo-caregiver-3"->"01000000004";case "demo-recipient-2"->"01000000005";case "demo-outsider"->"01000000006";case "demo-disabled"->"01000000007";default->throw new IllegalArgumentException(key);};}

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(request(path, "GET", null, token, null));
    }

    private HttpResponse<String> post(String path, String body, String token, String key) throws Exception {
        return send(request(path, "POST", body, token, key));
    }

    private HttpResponse<String> put(String path, String body, String token, String key) throws Exception {
        return send(request(path, "PUT", body, token, key));
    }

    private HttpRequest request(String path, String method, String body, String token, String key) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (token != null) builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String priorityBody(UUID memberId, int priority, long expectedVersion) {
        return "{\"items\":[{\"memberId\":\"" + memberId + "\",\"priority\":" + priority
                + ",\"expectedVersion\":" + expectedVersion + "}]}";
    }

    private long currentVersion(UUID memberId) {
        return jdbcTemplate.queryForObject("SELECT version FROM group_member WHERE id = ?", Long.class, memberId);
    }

    private void assertUnauthorized(HttpResponse<String> response) {
        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("UNAUTHORIZED"));
        assertTrue(response.body().contains("requestId"));
    }

    private void assertForbidden(HttpResponse<String> response, String code) {
        assertEquals(403, response.statusCode(), response.body());
        assertTrue(response.body().contains(code));
    }

    private record Login(String token, UUID userId) { }
}
