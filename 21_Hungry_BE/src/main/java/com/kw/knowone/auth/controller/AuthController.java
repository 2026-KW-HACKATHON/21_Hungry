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

    @PostMapping("/auth/signup")
    ResponseEntity<DataResponse<AuthService.SignupResult>> signup(@Valid @RequestBody SignupRequest request) {
        return ResponseEntity.status(201).body(DataResponse.of(
                authService.signup(request.phoneNumber(), request.accountRole(), request.displayName())));
    }

    @PostMapping("/auth/login")
    DataResponse<AuthService.LoginResult> phoneLogin(@Valid @RequestBody PhoneLoginRequest request) {
        return DataResponse.of(authService.loginByPhone(request.phoneNumber()));
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
    public record SignupRequest(@NotBlank @Size(max = 20) String phoneNumber, @NotBlank String accountRole,
            @Size(max = 50) String displayName) { }
    public record PhoneLoginRequest(@NotBlank @Size(max = 20) String phoneNumber) { }
}
