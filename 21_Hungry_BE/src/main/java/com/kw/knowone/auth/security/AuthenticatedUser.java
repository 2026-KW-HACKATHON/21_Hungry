package com.kw.knowone.auth.security;

import java.util.UUID;

public record AuthenticatedUser(UUID userId, UUID sessionId) {
}
