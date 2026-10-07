package com.kw.knowone.notification.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class NotificationDtos {
    private NotificationDtos() { }

    public record Target(String type, UUID id, UUID groupId) { }
    public record Item(UUID id, UUID groupId, UUID eventId, String eventType, String title, String body,
            OffsetDateTime readAt, OffsetDateTime createdAt, Target target) { }
    public record Page(List<Item> items, String nextCursor, boolean hasMore, long unreadCount) { }
    public record Preference(String handoffRepeat, long version) { }
    public record PreferenceUpdate(@NotNull @PositiveOrZero Long expectedVersion, @NotBlank String handoffRepeat) { }
    public record PushConfig(boolean enabled, String applicationServerKey) { }
    public record SubscriptionKeys(@NotBlank String p256dh, @NotBlank String auth) { }
    public record SubscriptionCreate(@NotBlank String endpoint, @NotNull @Valid SubscriptionKeys keys) { }
    public record SubscriptionCreated(UUID id, boolean enabled) { }
    public record SubscriptionMeta(UUID id, boolean enabled, OffsetDateTime createdAt, OffsetDateTime lastSeenAt) { }
    public record SubscriptionList(List<SubscriptionMeta> items) { }
}
