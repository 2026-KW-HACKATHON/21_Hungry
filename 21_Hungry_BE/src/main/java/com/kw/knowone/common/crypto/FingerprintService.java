package com.kw.knowone.common.crypto;

import java.util.Base64;
import org.springframework.stereotype.Component;
import com.kw.knowone.auth.service.AuthService;
import tools.jackson.databind.ObjectMapper;

@Component
public class FingerprintService {
    private final ObjectMapper objectMapper;

    public FingerprintService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String of(Object value) {
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(AuthService.sha256(objectMapper.writeValueAsString(value)));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Fingerprint serialization failed", exception);
        }
    }
}
