package com.kw.knowone.availability.controller;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.kw.knowone.auth.security.AuthenticatedUser;
import com.kw.knowone.availability.dto.AvailabilityDtos;
import com.kw.knowone.availability.service.MemberAvailabilityService;
import com.kw.knowone.common.web.DataResponse;

@RestController
@RequestMapping("/api/v1/care-groups/{groupId}/members/{memberId}")
public class MemberAvailabilityController {
    private final MemberAvailabilityService service;

    public MemberAvailabilityController(MemberAvailabilityService service) {
        this.service = service;
    }

    @GetMapping("/availability-days")
    DataResponse<AvailabilityDtos.Days> days(@PathVariable UUID groupId, @PathVariable UUID memberId,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate toDateExclusive,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return DataResponse.of(service.days(groupId, memberId, user.userId(), fromDate, toDateExclusive));
    }
}
