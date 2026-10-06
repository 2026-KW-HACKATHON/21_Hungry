package com.kw.knowone.group.entity;

import java.util.UUID;

public record CareGroup(UUID id, UUID recipientUserId, String recipientDisplayName, String name,
        String status, long version) {
}
