package com.kw.knowone.auth.controller;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.kw.knowone.auth.security.AuthenticatedUser;
import com.kw.knowone.auth.service.AuthService;
import com.kw.knowone.auth.service.AuthService.DemoAccount;
import com.kw.knowone.common.web.DataResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/auth/demo-accounts")
    DataResponse<DemoAccountsResponse> demoAccounts() {
        return DataResponse.of(new DemoAccountsResponse(authService.demoAccounts()));
    }

    @PostMapping("/auth/demo-login")
    DataResponse<AuthService.LoginResult> login(@Valid @RequestBody DemoLoginRequest request) {
        return DataResponse.of(authService.login(request.loginKey()));
    }

    @GetMapping("/me")
    DataResponse<AuthService.MeResult> me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return DataResponse.of(authService.me(principal.userId()));
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(@AuthenticationPrincipal AuthenticatedUser principal) {
        authService.logout(principal);
        return ResponseEntity.noContent().build();
    }

    public record DemoAccountsResponse(List<DemoAccount> items) { }
    public record DemoLoginRequest(@NotBlank @Size(max = 50) String loginKey) { }
}
