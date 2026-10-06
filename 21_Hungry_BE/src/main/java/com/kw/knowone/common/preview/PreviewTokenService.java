package com.kw.knowone.common.preview;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.kw.knowone.common.web.ApiException;
import tools.jackson.databind.ObjectMapper;

@Service
public class PreviewTokenService {
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration ttl;
    private final byte[] secret;

    public PreviewTokenService(ObjectMapper objectMapper, Clock clock,
            @Value("${app.preview.ttl:PT5M}") Duration ttl,
            @Value("${app.preview.signing-secret:}") String secret) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.ttl = ttl;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String issue(UUID userId, String operation, String payloadFingerprint, String stateFingerprint) {
        ensureConfigured();
        Instant now = clock.instant();
        Claims claims = new Claims(userId, operation, payloadFingerprint, stateFingerprint, now, now.plus(ttl));
        byte[] payload = write(claims);
        return encode(payload) + "." + encode(sign(payload));
    }

    public Claims verify(String token, UUID expectedUserId, String expectedOperation,
            String expectedPayloadFingerprint) {
        ensureConfigured();
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2) throw stale();
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            if (!java.security.MessageDigest.isEqual(signature, sign(payload))) throw stale();
            Claims claims = objectMapper.readValue(payload, Claims.class);
            if (!claims.userId().equals(expectedUserId)
                    || !claims.operation().equals(expectedOperation)
                    || !claims.payloadFingerprint().equals(expectedPayloadFingerprint)
                    || !claims.expiresAt().isAfter(clock.instant())) {
                throw stale();
            }
            return claims;
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw stale();
        }
    }

    private byte[] write(Claims claims) {
        try {
            return objectMapper.writeValueAsBytes(claims);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Preview serialization failed", exception);
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Preview signing failed", exception);
        }
    }

    private void ensureConfigured() {
        if (secret.length < 32) throw new IllegalStateException("PREVIEW_SIGNING_SECRET must contain at least 32 bytes");
    }

    private String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private ApiException stale() {
        return new ApiException(HttpStatus.CONFLICT, "PREVIEW_STALE", "미리보기 정보가 만료되었거나 현재 상태와 다릅니다.");
    }

    public record Claims(UUID userId, String operation, String payloadFingerprint, String stateFingerprint,
            Instant issuedAt, Instant expiresAt) { }
}
