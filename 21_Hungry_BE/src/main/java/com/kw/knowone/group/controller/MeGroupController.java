package com.kw.knowone.group.controller;

import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RestController;
import com.kw.knowone.auth.security.AuthenticatedUser;
import com.kw.knowone.common.web.DataResponse;
import com.kw.knowone.common.web.RequestIdFilter;
import com.kw.knowone.common.idempotency.IdempotentResult;
import jakarta.servlet.http.HttpServletRequest;
import com.kw.knowone.group.dto.GroupDtos;
import com.kw.knowone.group.service.GroupService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/me")
public class MeGroupController {
    private final GroupService service;

    public MeGroupController(GroupService service){this.service=service;}

    @GetMapping("/care-groups")
    DataResponse<GroupDtos.Items<GroupDtos.Group>> groups(@AuthenticationPrincipal AuthenticatedUser principal){
        return DataResponse.of(service.groups(principal.userId()));
    }

    @GetMapping("/join-requests/current")
    DataResponse<GroupDtos.CurrentJoin> current(@AuthenticationPrincipal AuthenticatedUser principal){
        return DataResponse.of(service.currentJoin(principal.userId()));
    }

    @PostMapping("/join-requests/{requestId}/cancel")
    ResponseEntity<String> cancel(@PathVariable UUID requestId,
            @Valid @RequestBody GroupDtos.CancelJoinRequest body,@RequestHeader(value="Idempotency-Key",required=false)String key,
            @AuthenticationPrincipal AuthenticatedUser principal,HttpServletRequest request){
        return response(service.cancelJoin(requestId,principal.userId(),body,key,UUID.fromString(RequestIdFilter.current(request))));
    }
    private ResponseEntity<String> response(IdempotentResult value){return ResponseEntity.status(value.status()).contentType(MediaType.APPLICATION_JSON).body(value.body());}
}
