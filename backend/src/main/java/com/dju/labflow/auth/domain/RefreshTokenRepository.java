package com.dju.labflow.auth.domain;

import com.dju.labflow.user.domain.UserId;
import java.time.Instant;
import java.util.Optional;

/** 토큰 저장, 잠금 조회, 폐기 및 만료 데이터 정리의 영속성 계약. */
public interface RefreshTokenRepository {

    // 새 Refresh Token 저장
    RefreshToken save(RefreshToken refreshToken);

    // 토큰 해시로 Refresh Token 조회
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // 재발급 동시성 제어를 위해 비관적 잠금으로 조회
    Optional<RefreshToken> findByTokenHashForUpdate(String tokenHash);

    // 아직 폐기되지 않은 Refresh Token 삭제
    void revoke(RefreshTokenId refreshTokenId, Instant revokedAt);

    // 특정 Refresh Token 삭제
    void delete(RefreshTokenId refreshTokenId);

    // 사용자의 모든 Refresh Token 삭제
    void deleteAllByUserId(UserId userId);

    // 만료된 Refresh Token 삭제
    void deleteExpiredTokens(Instant now);
}
