package com.dju.labflow.auth.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Table(
        name = "refresh_tokens",
        indexes = {
                @Index(
                        name = "idx_refresh_token_user_id",
                        columnList = "user_id"
                )
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_refresh_token_hash",
                        columnNames = "token_hash"
                )
        }
)
public class RefreshTokenEntity {

    @Id
    private Long id;

    private Long userId;

    private String tokenHash;

    private Instant expiresAt;

    private Instant createdAt;

    private Instant revokedAt;

    @Builder
    public RefreshTokenEntity(Long id, Long userId, String tokenHash, Instant expiresAt, Instant createdAt, Instant revokedAt) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.revokedAt = revokedAt;
    }

    public void updateRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }
}
