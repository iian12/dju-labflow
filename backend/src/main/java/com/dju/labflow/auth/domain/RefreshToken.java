package com.dju.labflow.auth.domain;

import com.dju.labflow.auth.domain.exception.AlreadyRevokedRefreshTokenException;
import com.dju.labflow.auth.domain.exception.ExpiredRefreshTokenException;
import com.dju.labflow.auth.domain.exception.RevokedRefreshTokenException;
import com.dju.labflow.user.domain.UserId;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;

/** 리프레시 토큰의 만료와 폐기 상태를 관리하는 도메인. 원문 대신 해시만 보관한다. */
@Getter
public class RefreshToken {

    private final RefreshTokenId id;

    private final UserId userId;

    // Refresh Token 원문의 SHA-256 해시값
    private final String tokenHash;

    private final Instant expiresAt;

    private final Instant createdAt;

    private Instant revokedAt;

    private RefreshToken(RefreshTokenId id, UserId userId, String tokenHash, Instant expiresAt, Instant createdAt, Instant revokedAt) {
        this.id = id;
        this.userId = Objects.requireNonNull(userId);
        this.tokenHash = Objects.requireNonNull(tokenHash);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.revokedAt = revokedAt;
    }

    public static RefreshToken create(RefreshTokenId id, UserId userId, String tokenHash, Instant expiresAt, Instant createdAt) {
        if (!expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("Refresh token expiration must be after creation.");

        return new RefreshToken(id, userId, tokenHash, expiresAt, createdAt, null);
    }

    public static RefreshToken restore(RefreshTokenId id, UserId userId, String tokenHash, Instant expiresAt, Instant createdAt, Instant revokedAt) {
        return new RefreshToken(id, userId, tokenHash, expiresAt, createdAt, revokedAt);
    }

    public void validateUsable(Instant now) {
        if (revokedAt != null) {
            throw new RevokedRefreshTokenException();
        }

        if (!expiresAt.isAfter(now)) {
            throw new ExpiredRefreshTokenException();
        }
    }

    public void revoke(Instant revokedAt) {
        Objects.requireNonNull(
                revokedAt,
                "revokedAt must not be null."
        );

        if (isRevoked()) {
            throw new AlreadyRevokedRefreshTokenException();
        }

        this.revokedAt = revokedAt;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
