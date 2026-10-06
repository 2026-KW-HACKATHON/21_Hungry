package com.kw.knowone.auth.entity;

import java.time.Instant;
import java.util.UUID;

public record AuthSession(UUID id, UUID userId, Instant expiresAt, Instant revokedAt, String userStatus) {
}
