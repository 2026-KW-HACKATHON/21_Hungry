package com.kw.knowone.task.controller;

import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.kw.knowone.auth.security.AuthenticatedUser;
import com.kw.knowone.common.idempotency.IdempotentResult;
import com.kw.knowone.common.web.DataResponse;
import com.kw.knowone.common.web.RequestIdFilter;
import com.kw.knowone.task.dto.TaskDtos;
import com.kw.knowone.task.service.TaskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1")
public class TaskController {
    private final TaskService service;
    public TaskController(TaskService service){this.service=service;}

    @GetMapping("/care-groups/{groupId}/tasks")
    DataResponse<TaskDtos.Page<TaskDtos.Task>> list(@PathVariable UUID groupId,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required=false) String executionStatus,@RequestParam(required=false) UUID assigneeUserId,
            @RequestParam(defaultValue="false") boolean unassigned,@RequestParam(defaultValue="false") boolean overdue,
            @RequestParam(required=false) UUID encounterId,@RequestParam(required=false) Integer limit,
            @RequestParam(required=false) String cursor,@AuthenticationPrincipal AuthenticatedUser user){
        return DataResponse.of(service.list(groupId,user.userId(),from,to,executionStatus,assigneeUserId,unassigned,overdue,encounterId,limit,cursor));}

    @GetMapping("/tasks/{occurrenceId}")
    DataResponse<TaskDtos.Task> detail(@PathVariable UUID occurrenceId,@AuthenticationPrincipal AuthenticatedUser user){return DataResponse.of(service.detail(occurrenceId,user.userId()));}
    @GetMapping("/task-series/{seriesId}")
    DataResponse<TaskDtos.Series> series(@PathVariable UUID seriesId,@AuthenticationPrincipal AuthenticatedUser user){return DataResponse.of(service.series(seriesId,user.userId()));}
    @PostMapping("/tasks/{occurrenceId}/series-edit-preview")
    DataResponse<TaskDtos.SeriesEditPreview> editPreview(@PathVariable UUID occurrenceId,
            @Valid @RequestBody TaskDtos.SeriesEditPreviewRequest body,@AuthenticationPrincipal AuthenticatedUser user){
        return DataResponse.of(service.editPreview(occurrenceId,user.userId(),body));}
    @PatchMapping("/tasks/{occurrenceId}")
    ResponseEntity<String> update(@PathVariable UUID occurrenceId,@Valid @RequestBody TaskDtos.UpdateRequest body,
            @RequestHeader(value="Idempotency-Key",required=false)String key,@AuthenticationPrincipal AuthenticatedUser user,
            HttpServletRequest request){return response(service.update(occurrenceId,user.userId(),body,key,requestId(request)));}
    @PostMapping("/tasks/{occurrenceId}/deletion-preview")
    DataResponse<TaskDtos.DeletionPreview> deletionPreview(@PathVariable UUID occurrenceId,
            @Valid @RequestBody TaskDtos.DeletionPreviewRequest body,@AuthenticationPrincipal AuthenticatedUser user){
        return DataResponse.of(service.deletionPreview(occurrenceId,user.userId(),body));}
    @DeleteMapping("/tasks/{occurrenceId}")
    ResponseEntity<String> delete(@PathVariable UUID occurrenceId,@Valid @RequestBody TaskDtos.DeleteRequest body,
            @RequestHeader(value="Idempotency-Key",required=false)String key,@AuthenticationPrincipal AuthenticatedUser user,
            HttpServletRequest request){return response(service.delete(occurrenceId,user.userId(),body,key,requestId(request)));}
    @PostMapping("/care-groups/{groupId}/task-series")
    ResponseEntity<String> create(@PathVariable UUID groupId,@Valid @RequestBody TaskDtos.CreateRequest body,
            @RequestHeader(value="Idempotency-Key",required=false)String key,@AuthenticationPrincipal AuthenticatedUser user,
            HttpServletRequest request){return response(service.create(groupId,user.userId(),body,key,requestId(request)));}
    @PutMapping("/tasks/{occurrenceId}/assignment")
    ResponseEntity<String> assign(@PathVariable UUID occurrenceId,@Valid @RequestBody TaskDtos.AssignmentRequest body,
            @RequestHeader(value="Idempotency-Key",required=false)String key,@AuthenticationPrincipal AuthenticatedUser user,
            HttpServletRequest request){return response(service.assign(occurrenceId,user.userId(),body,key,requestId(request)));}
    @PostMapping("/tasks/{occurrenceId}/complete")
    ResponseEntity<String> complete(@PathVariable UUID occurrenceId,@Valid @RequestBody TaskDtos.CompleteRequest body,
            @RequestHeader(value="Idempotency-Key",required=false)String key,@AuthenticationPrincipal AuthenticatedUser user,
            HttpServletRequest request){return response(service.complete(occurrenceId,user.userId(),body,key,requestId(request)));}
    @PostMapping("/tasks/{occurrenceId}/reopen")
    ResponseEntity<String> reopen(@PathVariable UUID occurrenceId,@Valid @RequestBody TaskDtos.ReopenRequest body,
            @RequestHeader(value="Idempotency-Key",required=false)String key,@AuthenticationPrincipal AuthenticatedUser user,
            HttpServletRequest request){return response(service.reopen(occurrenceId,user.userId(),body,key,requestId(request)));}
    @PostMapping("/tasks/{occurrenceId}/handoffs")
    ResponseEntity<String> requestHandoff(@PathVariable UUID occurrenceId,@Valid @RequestBody TaskDtos.HandoffRequest body,
            @RequestHeader(value="Idempotency-Key",required=false)String key,@AuthenticationPrincipal AuthenticatedUser user,
            HttpServletRequest request){return response(service.requestHandoff(occurrenceId,user.userId(),body,key,requestId(request)));}
    @PostMapping("/handoffs/{handoffId}/accept")
    ResponseEntity<String> acceptHandoff(@PathVariable UUID handoffId,@Valid @RequestBody TaskDtos.HandoffAcceptRequest body,
            @RequestHeader(value="Idempotency-Key",required=false)String key,@AuthenticationPrincipal AuthenticatedUser user,
            HttpServletRequest request){return response(service.acceptHandoff(handoffId,user.userId(),body,key,requestId(request)));}
    @PostMapping("/handoffs/{handoffId}/decline")
    ResponseEntity<String> declineHandoff(@PathVariable UUID handoffId,@Valid @RequestBody TaskDtos.HandoffDeclineRequest body,
            @RequestHeader(value="Idempotency-Key",required=false)String key,@AuthenticationPrincipal AuthenticatedUser user,
            HttpServletRequest request){return response(service.declineHandoff(handoffId,user.userId(),body,key,requestId(request)));}
    @GetMapping("/care-groups/{groupId}/handoffs")
    DataResponse<TaskDtos.Page<TaskDtos.HandoffItem>> handoffs(@PathVariable UUID groupId,
            @RequestParam(defaultValue="OPEN")String status,@RequestParam(required=false)Integer limit,
            @RequestParam(required=false)String cursor,@AuthenticationPrincipal AuthenticatedUser user){
        return DataResponse.of(service.handoffs(groupId,user.userId(),status,limit,cursor));}
    @GetMapping("/tasks/{occurrenceId}/history")
    DataResponse<TaskDtos.Page<TaskDtos.History>> history(@PathVariable UUID occurrenceId,
            @RequestParam(required=false)Integer limit,@RequestParam(required=false)String cursor,
            @AuthenticationPrincipal AuthenticatedUser user){return DataResponse.of(service.history(occurrenceId,user.userId(),limit,cursor));}
    private UUID requestId(HttpServletRequest request){return UUID.fromString(RequestIdFilter.current(request));}
    private ResponseEntity<String> response(IdempotentResult result){return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());}
}
