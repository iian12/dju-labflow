package com.dju.labflow.auth.infrastructure.persistence;

import com.dju.labflow.auth.domain.RefreshToken;
import com.dju.labflow.auth.domain.RefreshTokenId;
import com.dju.labflow.auth.domain.RefreshTokenRepository;
import com.dju.labflow.auth.domain.exception.RefreshTokenNotFoundException;
import com.dju.labflow.user.domain.UserId;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** 토큰 저장소의 JPA 어댑터. 도메인 객체와 관리 중인 엔티티를 구분해 갱신한다. */
@Repository
public class RefreshTokenRepositoryImpl implements RefreshTokenRepository {

    private final RefreshTokenJpaRepository jpaRepository;

    public RefreshTokenRepositoryImpl(RefreshTokenJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }


    @Override
    @Transactional
    public RefreshToken save(RefreshToken refreshToken) {
        Objects.requireNonNull(refreshToken, "Refresh Token must not be null");

        RefreshTokenEntity savedEntity = jpaRepository.save(RefreshTokenMapper.toEntity(refreshToken));

        return RefreshTokenMapper.toDomain(savedEntity);
    }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) return Optional.empty();

        return jpaRepository.findByTokenHash(tokenHash)
                .map(RefreshTokenMapper::toDomain);
    }

    @Override
    public Optional<RefreshToken> findByTokenHashForUpdate(String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) {
            return Optional.empty();
        }

        return jpaRepository.findByTokenHashForUpdate(tokenHash)
                .map(RefreshTokenMapper::toDomain);
    }

    @Override
    @Transactional
    public void revoke(
            RefreshTokenId refreshTokenId,
            Instant revokedAt
    ) {
        RefreshTokenEntity entity = jpaRepository
                .findById(refreshTokenId.value())
                .orElseThrow(RefreshTokenNotFoundException::new);

        RefreshToken refreshToken =
                RefreshTokenMapper.toDomain(entity);

        refreshToken.revoke(revokedAt);

        RefreshTokenMapper.updateEntity(
                entity,
                refreshToken
        );
    }

    @Override
    @Transactional
    public void delete(RefreshTokenId refreshTokenId) {
        jpaRepository.deleteById(
                refreshTokenId.value()
        );
    }

    @Override
    @Transactional
    public void deleteAllByUserId(UserId userId) {
        Objects.requireNonNull(userId, "User Id must not be null");

        jpaRepository.deleteAllByUserId(
                userId.value()
        );
    }

    @Override
    @Transactional
    public void deleteExpiredTokens(Instant now) {
        Objects.requireNonNull(now);

        jpaRepository.deleteByExpiresAtLessThanEqual(now);
    }
}
