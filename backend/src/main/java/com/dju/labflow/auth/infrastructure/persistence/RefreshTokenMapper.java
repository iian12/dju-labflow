package com.dju.labflow.auth.infrastructure.persistence;

import com.dju.labflow.auth.domain.RefreshToken;
import com.dju.labflow.auth.domain.RefreshTokenId;
import com.dju.labflow.user.domain.UserId;
import java.util.Objects;

/** 토큰 도메인과 JPA 엔티티를 변환하고 변경 가능한 폐기 시각만 갱신한다. */
public class RefreshTokenMapper {
    private RefreshTokenMapper() {
        /* This utility class should not be instantiated */
    }

    public static RefreshTokenEntity toEntity(RefreshToken refreshToken) {
        return RefreshTokenEntity.builder()
                .id(refreshToken.getId().value())
                .userId(refreshToken.getUserId().value())
                .tokenHash(refreshToken.getTokenHash())
                .expiresAt(refreshToken.getExpiresAt())
                .createdAt(refreshToken.getCreatedAt())
                .revokedAt(refreshToken.getRevokedAt())
                .build();
    }

    public static RefreshToken toDomain(RefreshTokenEntity entity) {
        return RefreshToken.restore(
                RefreshTokenId.of(entity.getId()),
                UserId.of(entity.getUserId()),
                entity.getTokenHash(),
                entity.getExpiresAt(),
                entity.getCreatedAt(),
                entity.getRevokedAt());
    }

    public static void updateEntity(RefreshTokenEntity entity, RefreshToken refreshToken) {
        Objects.requireNonNull(entity, "RefreshTokenEntity must not be null.");
        Objects.requireNonNull(refreshToken, "RefreshToken must not be null.");

        /*
         * Refresh Token은 발급 후 대부분의 값이 불변이므로
         * 변경 가능한 revokedAt만 영속 엔티티에 반영한다.
         */
        entity.updateRevokedAt(refreshToken.getRevokedAt());
    }
}
