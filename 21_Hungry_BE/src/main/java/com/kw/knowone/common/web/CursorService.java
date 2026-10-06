package com.kw.knowone.common.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class CursorService {
    public String encode(Instant time, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (time.toString() + "|" + id).getBytes(StandardCharsets.UTF_8));
    }
    public Value decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            return new Value(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "커서가 올바르지 않습니다.");
        }
    }
    public record Value(Instant time, UUID id) { }
}
