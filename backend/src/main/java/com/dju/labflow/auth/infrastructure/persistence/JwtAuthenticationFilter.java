package com.dju.labflow.auth.infrastructure.persistence;

import com.dju.labflow.auth.application.AccountAccessPolicy;
import com.dju.labflow.auth.domain.AccessTokenClaims;
import com.dju.labflow.auth.infrastructure.jwt.AccessTokenProvider;
import com.dju.labflow.auth.infrastructure.security.AppPrincipal;
import com.dju.labflow.user.domain.User;
import com.dju.labflow.user.domain.repository.UserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 사용자 JWT 쿠키를 검증하고 최신 계정 상태·권한으로 인증한다. SDK 경로는 API 키 필터가 담당한다. */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final AccessTokenProvider accessTokenProvider;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(
            AccessTokenProvider accessTokenProvider, UserRepository userRepository) {
        this.accessTokenProvider = accessTokenProvider;
        this.userRepository = userRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/api/v1/sdk") || path.startsWith("/api/v1/sdk/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String accessToken = extractAccessToken(request);

            if (accessToken != null
                    && SecurityContextHolder.getContext().getAuthentication() == null) {
                AccessTokenClaims claims = accessTokenProvider.parseAccessToken(accessToken);
                // 매 요청마다 현재 계정 상태와 권한을 읽어 차단/권한 회수를 즉시 반영한다.
                User user =
                        userRepository
                                .findById(claims.userId())
                                .orElseThrow(() -> new BadCredentialsException("Invalid account"));
                AccountAccessPolicy.requireActive(user);
                AppPrincipal principal = new AppPrincipal(user.getId(), user.getRole());

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                principal, null, principal.getAuthorities());
                authentication.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (JwtException | IllegalArgumentException | AuthenticationException e) {
            // 공개 경로는 계속 이용할 수 있지만 보호 경로는 미인증 요청으로 거부된다.
            SecurityContextHolder.clearContext();
        }
        // 하위 계층의 예외를 토큰 파싱 오류로 처리하거나 요청을 재실행하지 않는다.
        filterChain.doFilter(request, response);
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
