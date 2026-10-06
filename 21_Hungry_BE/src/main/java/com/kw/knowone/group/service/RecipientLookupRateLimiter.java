package com.kw.knowone.group.service;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import com.kw.knowone.common.web.ApiException;

@Component
public class RecipientLookupRateLimiter {
    private final Clock clock;
    private final int limit;
    private final Map<UUID, Window> windows = new HashMap<>();

    public RecipientLookupRateLimiter(Clock clock,
            @Value("${app.demo.recipient-lookup-limit-per-minute:10}") int limit) {
        this.clock = clock;
        this.limit = limit;
    }

    public synchronized void consume(UUID userId) {
        long minute = clock.instant().getEpochSecond() / 60;
        Window current = windows.get(userId);
        if (current == null || current.minute() != minute) {
            windows.put(userId, new Window(minute, 1));
            return;
        }
        if (current.count() >= limit) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "요청 횟수 제한을 초과했습니다.");
        }
        windows.put(userId, new Window(minute, current.count() + 1));
    }

    private record Window(long minute, int count) { }
}
