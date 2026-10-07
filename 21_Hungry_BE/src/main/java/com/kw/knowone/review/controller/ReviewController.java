package com.kw.knowone.review.controller;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.kw.knowone.auth.security.AuthenticatedUser;
import com.kw.knowone.common.idempotency.IdempotentResult;
import com.kw.knowone.common.web.DataResponse;
import com.kw.knowone.common.web.RequestIdFilter;
import com.kw.knowone.review.dto.ReviewDtos;
import com.kw.knowone.review.service.ReviewService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;

@RestController @RequestMapping("/api/v1")
public class ReviewController {
    private final ReviewService service;public ReviewController(ReviewService service){this.service=service;}
    @GetMapping("/encounters/{encounterId}/review-items") DataResponse<ReviewDtos.ReviewItems> items(@PathVariable UUID encounterId,@RequestParam(required=false)String reviewState,@AuthenticationPrincipal AuthenticatedUser user){return DataResponse.of(service.items(encounterId,user.userId(),reviewState));}
    @PostMapping("/encounters/{encounterId}/review-items/confirm") ResponseEntity<String> confirm(@PathVariable UUID encounterId,@Valid @RequestBody ReviewDtos.ConfirmRequest body,@RequestHeader(value="Idempotency-Key",required=false)String key,@AuthenticationPrincipal AuthenticatedUser user,HttpServletRequest request){return response(service.confirm(encounterId,user.userId(),body,key,requestId(request)));}
    @PostMapping("/encounters/{encounterId}/review-items/{itemId}/dismiss") ResponseEntity<String> dismiss(@PathVariable UUID encounterId,@PathVariable UUID itemId,@Valid @RequestBody ReviewDtos.DismissRequest body,@RequestHeader(value="Idempotency-Key",required=false)String key,@AuthenticationPrincipal AuthenticatedUser user,HttpServletRequest request){return response(service.dismiss(encounterId,itemId,user.userId(),body,key,requestId(request)));}
    @GetMapping("/care-groups/{groupId}/medications") DataResponse<ReviewDtos.MedicationPage> medications(@PathVariable UUID groupId,@RequestParam(required=false)UUID encounterId,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate onDate,@RequestParam(required=false)Integer limit,@RequestParam(required=false)String cursor,@AuthenticationPrincipal AuthenticatedUser user){return DataResponse.of(service.medications(groupId,user.userId(),encounterId,onDate,limit,cursor));}
    private ResponseEntity<String> response(IdempotentResult value){return ResponseEntity.status(value.status()).contentType(MediaType.APPLICATION_JSON).body(value.body());}
    private UUID requestId(HttpServletRequest request){return UUID.fromString(RequestIdFilter.current(request));}
}
