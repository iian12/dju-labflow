package com.dju.labflow.auth.infrastructure.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secretKey, Duration accessTokenExpiration, Duration refreshTokenExpiration) {
    public JwtProperties {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("JWT secret key must not be blank");
        }

        if (accessTokenExpiration == null || accessTokenExpiration.isZero() || accessTokenExpiration.isNegative()) {
            throw new IllegalArgumentException("Access token expiration must be positive");
        }

        if (refreshTokenExpiration == null || refreshTokenExpiration.isZero() || refreshTokenExpiration.isNegative()) {
            throw new IllegalArgumentException("Refresh token expiration must be positive");
        }
    }
}
