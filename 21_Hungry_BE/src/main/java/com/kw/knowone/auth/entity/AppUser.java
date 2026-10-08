package com.kw.knowone.auth.entity;

import java.util.UUID;

public record AppUser(UUID id, String loginKey, String displayName, String phoneNumber, String accountRole, String accountType,
        String status, String timezone, int activeStartMinute, int activeEndMinute, long version) {
}
