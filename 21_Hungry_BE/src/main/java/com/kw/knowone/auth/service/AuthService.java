package com.kw.knowone.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kw.knowone.auth.entity.AppUser;
import com.kw.knowone.auth.entity.AuthSession;
import com.kw.knowone.auth.repository.AuthRepository;
import com.kw.knowone.auth.security.AuthenticatedUser;
import com.kw.knowone.common.web.ApiException;

@Service
public class AuthService {
    private final AuthRepository repository;
    private final Clock clock;
    private final boolean demoEnabled;
    private final Duration sessionTtl;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(AuthRepository repository, Clock clock,
            @Value("${app.demo.enabled:false}") boolean demoEnabled,
            @Value("${app.auth.session-ttl:PT24H}") Duration sessionTtl) {
        this.repository = repository;
        this.clock = clock;
        this.demoEnabled = demoEnabled;
        this.sessionTtl = sessionTtl;
    }

    public List<DemoAccount> demoAccounts() {
        requireDemoMode();
        return repository.findActiveDemoUsers().stream()
                .map(user -> new DemoAccount(user.loginKey(), user.displayName())).toList();
    }

    @Transactional
    public LoginResult login(String loginKey) {
        requireDemoMode();
        AppUser user = repository.findActiveDemoByLoginKey(loginKey)
                .orElseThrow(this::unauthorized);
        byte[] rawToken = new byte[32];
        secureRandom.nextBytes(rawToken);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(rawToken);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(sessionTtl);
        repository.createSession(UUID.randomUUID(), user.id(), sha256(token), expiresAt, now);
        return loginResult(user, token, expiresAt);
    }

    @Transactional
    public SignupResult signup(String rawPhoneNumber, String accountRole, String rawDisplayName) {
        String phoneNumber = normalizePhone(rawPhoneNumber);
        if (!"PARENT".equals(accountRole) && !"CHILD".equals(accountRole)) {
            throw validation("accountRole을 확인해 주세요.");
        }
        String displayName = rawDisplayName == null ? null : rawDisplayName.trim();
        if ("PARENT".equals(accountRole)) {
            if (displayName != null) throw validation("부모 가입에는 displayName을 입력하지 않습니다.");
        } else if (displayName == null || displayName.isEmpty() || displayName.length() > 50) {
            throw validation("자녀 이름은 1~50자여야 합니다.");
        }
        UUID userId = UUID.randomUUID();
        Instant now = clock.instant();
        if (repository.insertUser(userId, phoneNumber, accountRole, displayName, now) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "PHONE_ALREADY_REGISTERED", "이미 가입된 전화번호입니다.");
        }
        UUID groupId = "PARENT".equals(accountRole) ? repository.createParentGroup(userId, now) : null;
        return new SignupResult(new UserRef(userId, displayName), accountRole, groupId, "LOGIN");
    }

    @Transactional
    public LoginResult loginByPhone(String rawPhoneNumber) {
        AppUser user = repository.findActiveByPhoneNumber(normalizePhone(rawPhoneNumber)).orElseThrow(this::unauthorized);
        byte[] rawToken = new byte[32];
        secureRandom.nextBytes(rawToken);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(rawToken);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(sessionTtl);
        repository.createSession(UUID.randomUUID(), user.id(), sha256(token), expiresAt, now);
        return loginResult(user, token, expiresAt);
    }

    public AuthenticatedUser authenticate(String token) {
        AuthSession session = repository.findSession(sha256(token)).orElseThrow(this::unauthorized);
        Instant now = clock.instant();
        if (session.revokedAt() != null || !session.expiresAt().isAfter(now) || !"ACTIVE".equals(session.userStatus())) {
            throw unauthorized();
        }
        return new AuthenticatedUser(session.userId(), session.id());
    }

    public MeResult me(UUID userId) {
        AppUser user = repository.findActiveById(userId).orElseThrow(this::unauthorized);
        AuthRepository.MembershipSummary membership = repository.findCurrentMembership(userId).orElse(null);
        String onboarding = membership == null ? "NO_GROUP"
                : "PENDING".equals(membership.status()) ? "WAITING_APPROVAL"
                : membership.parentProfileCompleted() ? "READY" : "PARENT_PROFILE_PENDING";
        MembershipRef ref = membership == null ? null : new MembershipRef(membership.groupId(), membership.status(),
                membership.priority(), membership.joinRequestId());
        return new MeResult(user.id(), user.displayName(), user.accountRole(), user.phoneNumber(), ref, onboarding);
    }

    @Transactional
    public void logout(AuthenticatedUser principal) {
        repository.revokeSession(principal.sessionId(), principal.userId(), clock.instant());
    }

    public void requireDemoMode() {
        if (!demoEnabled) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEMO_ONLY", "시연 환경에서만 사용할 수 있습니다.");
        }
    }

    public static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증 정보가 올바르지 않습니다.");
    }

    private String normalizePhone(String value) {
        String normalized = value == null ? "" : value.replaceAll("[-\\s]", "");
        if (!normalized.matches("^010[0-9]{8}$")) throw validation("전화번호 형식을 확인해 주세요.");
        return normalized;
    }

    private ApiException validation(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private LoginResult loginResult(AppUser user, String token, Instant expiresAt) {
        return new LoginResult(token, "Bearer", OffsetDateTime.ofInstant(expiresAt, clock.getZone()),
                new UserRef(user.id(), user.displayName()), user.accountRole());
    }

    public record DemoAccount(String loginKey, String displayName) { }
    public record LoginResult(String accessToken, String tokenType, OffsetDateTime expiresAt, UserRef user,
            String accountRole) { }
    public record UserRef(UUID id, String displayName) { }
    public record SignupResult(UserRef user, String accountRole, UUID groupId, String nextAction) { }
    public record MembershipRef(UUID groupId, String status, Integer priority, UUID joinRequestId) { }
    public record MeResult(UUID id, String displayName, String accountRole, String phoneNumber,
            MembershipRef membership, String onboardingState) { }
}
