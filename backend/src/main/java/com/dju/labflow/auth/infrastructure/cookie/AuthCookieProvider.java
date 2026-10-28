package com.dju.labflow.auth.infrastructure.cookie;

import com.dju.labflow.auth.infrastructure.jwt.JwtProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** 인증 쿠키 생성과 삭제의 경로, HttpOnly, Secure, SameSite 속성을 일치시킨다. */
@Component
public class AuthCookieProvider {

    private static final String ACCESS_TOKEN_COOKIE_NAME = "access_token";
    private static final String REFRESH_TOKEN_COOKIE_NAME = "refresh_token";
    private final Clock clock;

    private final Duration accessTokenExpiration;

    private final boolean secure;
    private final String sameSite;

    public AuthCookieProvider(
            JwtProperties jwtProperties, AuthCookieProperties authCookieProperties, Clock clock) {
        this.clock = clock;
        this.accessTokenExpiration = jwtProperties.accessTokenExpiration();
        this.secure = authCookieProperties.secure();
        this.sameSite = authCookieProperties.sameSite();
    }

    public ResponseCookie createAccessTokenCookie(String accessToken) {
        return ResponseCookie.from(ACCESS_TOKEN_COOKIE_NAME, accessToken)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(accessTokenExpiration)
                .build();
    }

    public ResponseCookie createRefreshTokenCookie(String refreshToken, Instant expiresAt) {
        Duration maxAge = Duration.between(clock.instant(), expiresAt);
        if (maxAge.isNegative()) maxAge = Duration.ZERO;
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE_NAME, refreshToken)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/api/v1/auth")
                .maxAge(maxAge)
                .build();
    }

    public ResponseCookie deleteAccessTokenCookie() {
        return ResponseCookie.from(ACCESS_TOKEN_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(0)
                .build();
    }

    public ResponseCookie deleteRefreshTokenCookie() {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/api/v1/auth")
                .maxAge(0)
                .build();
    }
}
