package com.dju.labflow.project.key.infrastructure.security;

import com.dju.labflow.project.key.application.ProjectApiKeyAuthenticationService;
import com.dju.labflow.project.key.domain.ProjectApiKeyPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** SDK 경로의 Bearer API 키만 처리하고 일반 사용자 쿠키 인증과 분리한다. */
@Component
public class ProjectApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final ProjectApiKeyAuthenticationService authenticationService;

    private final AuthenticationEntryPoint authenticationEntryPoint;

    public ProjectApiKeyAuthenticationFilter(ProjectApiKeyAuthenticationService authenticationService, AuthenticationEntryPoint authenticationEntryPoint) {
        this.authenticationService = authenticationService;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return "OPTIONS".equals(request.getMethod())
                || !(path.equals("/api/v1/sdk") || path.startsWith("/api/v1/sdk/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        try {
            String rawApiKey = resolveApiKey(request);

            if (rawApiKey == null) {
                authenticationEntryPoint.commence(request, response, new BadCredentialsException("Project API key is required"));

                return;
            }

            ProjectApiKeyPrincipal principal = authenticationService.authenticate(rawApiKey);

            ProjectApiKeyAuthenticationToken authentication = new ProjectApiKeyAuthenticationToken(principal);

            SecurityContext context = SecurityContextHolder.createEmptyContext();

            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        } catch (AuthenticationException e) {
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(request, response, e);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String resolveApiKey(
            HttpServletRequest request
    ) {

        String authorization =
                request.getHeader(
                        HttpHeaders.AUTHORIZATION
                );

        if (authorization == null ||
                !authorization.startsWith(PREFIX)) {

            return null;
        }

        String rawApiKey =
                authorization.substring(
                        PREFIX.length()
                ).trim();

        if (rawApiKey.isEmpty()) {
            return null;
        }

        return rawApiKey;
    }

}
