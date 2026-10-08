package com.kw.knowone.group.controller;

import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.time.LocalDate;
import com.kw.knowone.task.service.TaskService;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.kw.knowone.auth.security.AuthenticatedUser;
import com.kw.knowone.common.idempotency.IdempotentResult;
import com.kw.knowone.common.web.DataResponse;
import com.kw.knowone.common.web.ApiException;
import org.springframework.http.HttpStatus;
import com.kw.knowone.common.web.RequestIdFilter;
import com.kw.knowone.group.dto.GroupDtos;
import com.kw.knowone.group.service.GroupService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/care-groups")
public class GroupController {
    private final GroupService groupService;
    private final TaskService taskService;

    public GroupController(GroupService groupService, TaskService taskService) {
        this.groupService = groupService;
        this.taskService = taskService;
    }

    @PostMapping("/{groupId}/memberships/me/leave")
    ResponseEntity<String> leave(@PathVariable UUID groupId,@Valid @RequestBody GroupDtos.LeaveRequest body,
            @RequestHeader(value="Idempotency-Key",required=false)String key,
            @AuthenticationPrincipal AuthenticatedUser principal,HttpServletRequest request){
        return response(groupService.leave(groupId,principal.userId(),body,key,
                UUID.fromString(RequestIdFilter.current(request))));
    }

    @GetMapping("/{groupId}/home")
    DataResponse<GroupDtos.Home> home(@PathVariable UUID groupId,@RequestParam(required=false)LocalDate date,
            @RequestParam(required=false)Integer limit,@AuthenticationPrincipal AuthenticatedUser principal){
        throw new ApiException(HttpStatus.GONE,"ENDPOINT_RETIRED","폐기된 API입니다.");
    }

    @GetMapping
    DataResponse<GroupDtos.Items<GroupDtos.Group>> groups(@AuthenticationPrincipal AuthenticatedUser principal) {
        throw retired();
    }

    @GetMapping("/{groupId}")
    DataResponse<GroupDtos.Group> group(@PathVariable UUID groupId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return DataResponse.of(groupService.group(groupId, principal.userId()));
    }

    @PostMapping("/recipient-lookup")
    DataResponse<GroupDtos.RecipientLookup> lookup(@Valid @RequestBody GroupDtos.RecipientLookupRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return DataResponse.of(groupService.lookup(principal.userId(), request.phoneNumber()));
    }

    @PostMapping("/{groupId}/memberships")
    ResponseEntity<String> join(@PathVariable UUID groupId, @Valid @RequestBody GroupDtos.JoinRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal AuthenticatedUser principal, HttpServletRequest request) {
        throw retired();
    }

    @PostMapping("/{groupId}/join")
    ResponseEntity<String> joinV11(@PathVariable UUID groupId,@Valid @RequestBody GroupDtos.JoinV11Request body,
            @RequestHeader(value="Idempotency-Key",required=false)String key,
            @AuthenticationPrincipal AuthenticatedUser principal,HttpServletRequest request){
        return response(groupService.joinV11(groupId,principal.userId(),body,key,
                UUID.fromString(RequestIdFilter.current(request))));
    }

    @GetMapping("/{groupId}/join-requests")
    DataResponse<GroupDtos.JoinRequestPage> joinRequests(@PathVariable UUID groupId,
            @RequestParam(required=false)Integer limit,@RequestParam(required=false)String cursor,
            @AuthenticationPrincipal AuthenticatedUser principal){
        return DataResponse.of(groupService.pendingJoins(groupId,principal.userId(),limit,cursor));
    }

    @PostMapping("/{groupId}/join-requests/{joinRequestId}/decision")
    ResponseEntity<String> decideJoin(@PathVariable UUID groupId,@PathVariable UUID joinRequestId,
            @Valid @RequestBody GroupDtos.JoinDecisionRequest body,@RequestHeader(value="Idempotency-Key",required=false)String key,
            @AuthenticationPrincipal AuthenticatedUser principal,HttpServletRequest request){
        return response(groupService.decideJoin(groupId,joinRequestId,principal.userId(),body,key,
                UUID.fromString(RequestIdFilter.current(request))));
    }

    @GetMapping("/{groupId}/members")
    DataResponse<GroupDtos.Items<GroupDtos.Member>> members(@PathVariable UUID groupId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return DataResponse.of(groupService.members(groupId, principal.userId()));
    }

    @PutMapping("/{groupId}/member-priorities")
    ResponseEntity<String> priorities(@PathVariable UUID groupId, @Valid @RequestBody GroupDtos.PriorityRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal AuthenticatedUser principal, HttpServletRequest request) {
        return response(groupService.updatePriorities(groupId, principal.userId(), body, idempotencyKey,
                UUID.fromString(RequestIdFilter.current(request))));
    }

    private ResponseEntity<String> response(IdempotentResult result) {
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
    }
    private ApiException retired(){return new ApiException(HttpStatus.GONE,"ENDPOINT_RETIRED","폐기된 API입니다.");}
}
