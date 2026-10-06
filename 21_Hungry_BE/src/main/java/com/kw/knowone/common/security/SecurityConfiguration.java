package com.kw.knowone.common.security;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import com.kw.knowone.auth.security.BearerTokenAuthenticationFilter;
import com.kw.knowone.common.config.CorsProperties;
import com.kw.knowone.common.web.ApiErrorWriter;
import com.kw.knowone.common.web.RequestIdFilter;

@Configuration
public class SecurityConfiguration {
    private static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    private static final List<String> ALLOWED_HEADERS = List.of(
            HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, "Idempotency-Key", RequestIdFilter.HEADER_NAME);

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, BearerTokenAuthenticationFilter bearerFilter,
            ApiErrorWriter errorWriter) throws Exception {
        return http.cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/internal/status", "/actuator/health/liveness", "/actuator/health/readiness",
                                "/api/v1/auth/demo-accounts", "/api/v1/auth/demo-login").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> errorWriter.write(response,
                                HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다.", request))
                        .accessDeniedHandler((request, response, exception) -> errorWriter.write(response,
                                HttpStatus.FORBIDDEN, "FORBIDDEN", "요청을 수행할 권한이 없습니다.", request)))
                .addFilterBefore(bearerFilter, AnonymousAuthenticationFilter.class)
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.getAllowedOrigins());
        configuration.setAllowedMethods(ALLOWED_METHODS);
        configuration.setAllowedHeaders(ALLOWED_HEADERS);
        configuration.setExposedHeaders(List.of(HttpHeaders.CONTENT_DISPOSITION, HttpHeaders.RETRY_AFTER,
                RequestIdFilter.HEADER_NAME));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
