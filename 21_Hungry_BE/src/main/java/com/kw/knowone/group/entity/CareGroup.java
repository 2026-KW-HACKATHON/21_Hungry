package com.kw.knowone.group.entity;

import java.util.UUID;
import java.time.Instant;

public record CareGroup(UUID id, UUID recipientUserId, String recipientDisplayName, String name,
        String status, long version, String parentRelation, Integer parentBirthYear,
        Instant parentProfileCompletedAt) {
}
