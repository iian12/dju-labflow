package com.dju.labflow.auth.infrastructure.persistence;

import jakarta.persistence.*;
import com.dju.labflow.global.id.AssignedIdEntity;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 토큰 해시, 만료 및 폐기 시각의 JPA 저장 모델. 해시는 중복을 허용하지 않는다. */
@Entity
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Table(
        name = "refresh_tokens",
        indexes = {
                @Index(
                        name = "idx_refresh_token_user_id",
                        columnList = "user_id"
                ),
                @Index(name = "idx_refresh_token_expires_at", columnList = "expires_at")
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_refresh_token_hash",
                        columnNames = "token_hash"
                )
        }
)
public class RefreshTokenEntity extends AssignedIdEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 43)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(nullable = false, updatable = false)
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
