package com.kw.knowone.common.status;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/status")
public class StatusController {

    private final Clock clock;

    public StatusController(Clock clock) {
        this.clock = clock;
    }

    @GetMapping
    Map<String, StatusResponse> status() {
        return Map.of("data", new StatusResponse("UP", OffsetDateTime.now(clock)));
    }

    record StatusResponse(String status, OffsetDateTime timestamp) {
    }
}
