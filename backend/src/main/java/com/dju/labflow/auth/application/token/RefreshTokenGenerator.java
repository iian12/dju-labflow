package com.dju.labflow.auth.application.token;

import com.dju.labflow.auth.infrastructure.jwt.JwtProperties;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

@Component
public class RefreshTokenGenerator {

    private static final int TOKEN_BYTE_LENGTH = 32;

    private final SecureRandom secureRandom;

    private final long refreshTokenExpirationMillis;

    private final Clock clock;

    public RefreshTokenGenerator(SecureRandom secureRandom, JwtProperties properties, Clock clock) {
        this.secureRandom = secureRandom;
        this.refreshTokenExpirationMillis = properties.refreshTokenExpiration().toMillis();
        this.clock = clock;
    }

    public IssuedRefreshToken generate() {
        byte[] randomBytes = new byte[TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(randomBytes);

        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        Instant expiresAt = clock.instant().plusMillis(refreshTokenExpirationMillis);

        return new IssuedRefreshToken(rawToken, expiresAt);
    }
}
