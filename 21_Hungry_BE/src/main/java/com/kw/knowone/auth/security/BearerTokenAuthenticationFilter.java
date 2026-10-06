package com.kw.knowone.auth.security;

import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.kw.knowone.auth.service.AuthService;
import com.kw.knowone.common.web.ApiErrorWriter;
import com.kw.knowone.common.web.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {
    private final AuthService authService;
    private final ApiErrorWriter errorWriter;

    public BearerTokenAuthenticationFilter(AuthService authService, ApiErrorWriter errorWriter) {
        this.authService = authService;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null) {
            chain.doFilter(request, response);
            return;
        }
        if (!authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            errorWriter.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증 정보가 올바르지 않습니다.", request);
            return;
        }
        AuthenticatedUser principal;
        try {
            principal = authService.authenticate(authorization.substring(7));
        } catch (ApiException exception) {
            SecurityContextHolder.clearContext();
            errorWriter.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증 정보가 올바르지 않습니다.", request);
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, java.util.List.of()));
        chain.doFilter(request, response);
    }
}
