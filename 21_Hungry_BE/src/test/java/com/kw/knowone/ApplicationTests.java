package com.kw.knowone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationTests {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoads() {
    }

    @Test
    void flywayCreatesTheTwentyEightApplicationTablesAndGuardRow() {
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'hungry_test'
                  AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                """, Integer.class);
        Integer guardCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hungry_test.schedule_guard WHERE id = 1",
                Integer.class);

        assertEquals(28, tableCount);
        assertEquals(1, guardCount);
    }

    @Test
    void statusEndpointIsPublicAndUsesKoreanOffset() throws IOException, InterruptedException {
        String requestId = UUID.randomUUID().toString();
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/internal/status"))
                .header("X-Request-Id", requestId)
                .GET()
                .build());

        assertEquals(200, response.statusCode());
        assertEquals(requestId, response.headers().firstValue("X-Request-Id").orElseThrow());
        assertTrue(response.body().contains("\"status\":\"UP\""));
        assertTrue(response.body().contains("+09:00"));
    }

    @Test
    void businessPathsAreProtectedWithCommonErrorEnvelope() throws IOException, InterruptedException {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/v1/care-groups"))
                .header("Origin", "http://localhost:5173")
                .GET()
                .build());

        assertEquals(401, response.statusCode());
        assertEquals(
                "http://localhost:5173",
                response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("X-Request-Id").isPresent());
        assertTrue(response.body().contains("\"code\":\"UNAUTHORIZED\""));
        assertTrue(response.body().contains("\"requestId\":"));
        assertTrue(response.body().contains("\"details\":{}"));
    }

    @Test
    void allowedOriginCanPreflightWithoutAuthentication() throws IOException, InterruptedException {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/v1/care-groups"))
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization,Content-Type,Idempotency-Key")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build());

        assertEquals(200, response.statusCode());
        assertEquals(
                "http://localhost:5173",
                response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
    }

    @Test
    void unregisteredOriginDoesNotReceiveCorsPermission() throws IOException, InterruptedException {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/internal/status"))
                .header("Origin", "https://unregistered.example")
                .GET()
                .build());

        assertFalse(response.headers().firstValue("Access-Control-Allow-Origin").isPresent());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
