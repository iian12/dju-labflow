package com.dju.labflow.auth.infrastructure.persistence;

import com.dju.labflow.auth.domain.AccessTokenClaims;
import com.dju.labflow.auth.infrastructure.jwt.AccessTokenProvider;
import com.dju.labflow.auth.infrastructure.security.AppPrincipal;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final AccessTokenProvider accessTokenProvider;

    public JwtAuthenticationFilter(AccessTokenProvider accessTokenProvider) {
        this.accessTokenProvider = accessTokenProvider;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/api/v1/sdk") || path.startsWith("/api/v1/sdk/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        try {
            String accessToken = extractAccessToken(request);

            if (accessToken != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                AccessTokenClaims claims = accessTokenProvider.parseAccessToken(accessToken);
                AppPrincipal principal = new AppPrincipal(claims.userId(), claims.role());

                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
            filterChain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException e) {
            // 잘못되거나 만료된 Access Token은 인증 객체를 생성하지 않음.
            SecurityContextHolder.clearContext();
            filterChain.doFilter(request, response);
        }
    }

    private String extractAccessToken(HttpServletRequest request) {
        if (request.getCookies() == null) return null;

        return Arrays.stream(request.getCookies())
                .filter(cookie -> "access_token".equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
