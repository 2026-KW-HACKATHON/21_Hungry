package com.kw.knowone.notification.controller;

import com.kw.knowone.auth.security.AuthenticatedUser;
import com.kw.knowone.common.web.DataResponse;
import com.kw.knowone.notification.dto.NotificationDtos;
import com.kw.knowone.notification.service.NotificationService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class NotificationController {
    private final NotificationService service;
    public NotificationController(NotificationService service) { this.service = service; }

    @GetMapping("/me/notifications")
    ResponseEntity<DataResponse<NotificationDtos.Page>> list(@RequestParam(required=false) UUID groupId,
            @RequestParam(defaultValue="false") boolean unreadOnly, @RequestParam(required=false) Integer limit,
            @RequestParam(required=false) String cursor, @AuthenticationPrincipal AuthenticatedUser user) {
        return ok(service.list(user.userId(), groupId, unreadOnly, limit, cursor));
    }

    @PostMapping("/me/notifications/{notificationId}/read")
    ResponseEntity<DataResponse<NotificationDtos.Item>> read(@PathVariable UUID notificationId,
            @AuthenticationPrincipal AuthenticatedUser user) { return ok(service.read(user.userId(), notificationId)); }

    @GetMapping("/me/notification-preferences")
    ResponseEntity<DataResponse<NotificationDtos.Preference>> preference(@AuthenticationPrincipal AuthenticatedUser user) {
        return ok(service.preference(user.userId()));
    }

    @PutMapping("/me/notification-preferences")
    ResponseEntity<DataResponse<NotificationDtos.Preference>> updatePreference(@Valid @RequestBody NotificationDtos.PreferenceUpdate body,
            @AuthenticationPrincipal AuthenticatedUser user) { return ok(service.updatePreference(user.userId(), body)); }

    @GetMapping("/push/config")
    ResponseEntity<DataResponse<NotificationDtos.PushConfig>> config() { return ok(service.pushConfig()); }

    @PostMapping("/me/push-subscriptions")
    ResponseEntity<DataResponse<NotificationDtos.SubscriptionCreated>> subscribe(@Valid @RequestBody NotificationDtos.SubscriptionCreate body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        NotificationService.Created value=service.subscribe(user.userId(),body);
        return ResponseEntity.status(value.created()?201:200).cacheControl(CacheControl.noStore())
                .body(DataResponse.of(value.value()));
    }

    @GetMapping("/me/push-subscriptions")
    ResponseEntity<DataResponse<NotificationDtos.SubscriptionList>> subscriptions(@AuthenticationPrincipal AuthenticatedUser user) {
        return ok(service.subscriptions(user.userId()));
    }

    @DeleteMapping("/me/push-subscriptions/{subscriptionId}")
    ResponseEntity<Void> unsubscribe(@PathVariable UUID subscriptionId,@AuthenticationPrincipal AuthenticatedUser user) {
        service.unsubscribe(user.userId(),subscriptionId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private <T> ResponseEntity<DataResponse<T>> ok(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(DataResponse.of(value));
    }
}
