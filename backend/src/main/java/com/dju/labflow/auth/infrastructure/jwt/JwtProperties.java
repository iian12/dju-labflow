package com.dju.labflow.auth.infrastructure.jwt;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 필수 JWT 서명 키와 양수인 토큰 수명을 애플리케이션 시작 시 검증한다. */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String secretKey, Duration accessTokenExpiration, Duration refreshTokenExpiration) {
    public JwtProperties {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("JWT secret key must not be blank");
        }

        if (accessTokenExpiration == null
                || accessTokenExpiration.isZero()
                || accessTokenExpiration.isNegative()) {
            throw new IllegalArgumentException("Access token expiration must be positive");
        }

        if (refreshTokenExpiration == null
                || refreshTokenExpiration.isZero()
                || refreshTokenExpiration.isNegative()) {
            throw new IllegalArgumentException("Refresh token expiration must be positive");
        }
    }
}
