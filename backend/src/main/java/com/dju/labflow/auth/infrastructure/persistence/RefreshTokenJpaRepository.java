package com.dju.labflow.auth.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 토큰 조회와 삭제 쿼리. 회전 시에는 쓰기 잠금으로 같은 토큰의 동시 사용을 직렬화한다. */
public interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenEntity, Long> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            select rt
            from RefreshTokenEntity rt
            where rt.tokenHash = :tokenHash
            """)
    Optional<RefreshTokenEntity> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    void deleteAllByUserId(Long userId);

    int deleteByExpiresAtLessThanEqual(Instant now);
}
