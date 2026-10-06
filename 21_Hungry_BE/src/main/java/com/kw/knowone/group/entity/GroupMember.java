package com.kw.knowone.group.entity;

import java.util.UUID;

public record GroupMember(UUID id, UUID groupId, UUID userId, String displayName, String role,
        Integer priority, String status, long version) {
}
