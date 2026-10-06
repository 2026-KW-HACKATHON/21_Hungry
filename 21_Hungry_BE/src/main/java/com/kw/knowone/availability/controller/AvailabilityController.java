package com.kw.knowone.availability.controller;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.kw.knowone.auth.security.AuthenticatedUser;
import com.kw.knowone.availability.dto.AvailabilityDtos;
import com.kw.knowone.availability.service.AvailabilityService;
import com.kw.knowone.common.idempotency.IdempotentResult;
import com.kw.knowone.common.web.DataResponse;
import com.kw.knowone.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/me")
public class AvailabilityController {
    private final AvailabilityService service;
    public AvailabilityController(AvailabilityService service) { this.service = service; }

    @GetMapping("/availability-config")
    DataResponse<AvailabilityDtos.Config> config(@AuthenticationPrincipal AuthenticatedUser user) {
        return DataResponse.of(service.config(user.userId()));
    }
    @PostMapping("/availability-config/preview")
    DataResponse<AvailabilityDtos.Preview> previewConfig(@Valid @RequestBody AvailabilityDtos.ConfigPreviewRequest body,
            @AuthenticationPrincipal AuthenticatedUser user) { return DataResponse.of(service.previewConfig(user.userId(), body)); }
    @PatchMapping("/availability-config")
    ResponseEntity<String> saveConfig(@Valid @RequestBody AvailabilityDtos.ConfigSaveRequest body,
            @RequestHeader(value="Idempotency-Key", required=false) String key,
            @AuthenticationPrincipal AuthenticatedUser user, HttpServletRequest request) {
        return response(service.saveConfig(user.userId(), body, key, requestId(request)));
    }
    @GetMapping("/availability-days")
    DataResponse<AvailabilityDtos.Days> days(
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate toDateExclusive,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return DataResponse.of(service.days(user.userId(), fromDate, toDateExclusive));
    }
    @PostMapping("/availability-days/preview")
    DataResponse<AvailabilityDtos.DaysPreview> previewDays(@Valid @RequestBody AvailabilityDtos.DaysRequest body,
            @AuthenticationPrincipal AuthenticatedUser user) { return DataResponse.of(service.previewDays(user.userId(), body)); }
    @PutMapping("/availability-days")
    ResponseEntity<String> saveDays(@Valid @RequestBody AvailabilityDtos.DaysSaveRequest body,
            @RequestHeader(value="Idempotency-Key", required=false) String key,
            @AuthenticationPrincipal AuthenticatedUser user, HttpServletRequest request) {
        return response(service.saveDays(user.userId(), body, key, requestId(request)));
    }
    private UUID requestId(HttpServletRequest request) { return UUID.fromString(RequestIdFilter.current(request)); }
    private ResponseEntity<String> response(IdempotentResult result) { return ResponseEntity.status(result.status())
            .contentType(MediaType.APPLICATION_JSON).body(result.body()); }
}
