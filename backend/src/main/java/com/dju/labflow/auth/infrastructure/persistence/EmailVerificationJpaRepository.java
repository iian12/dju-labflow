package com.dju.labflow.auth.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 같은 사용자의 코드 검증과 재발송을 직렬화하는 JPA 쿼리. */
public interface EmailVerificationJpaRepository
        extends JpaRepository<EmailVerificationEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select verification from EmailVerificationEntity verification where verification.id = :userId")
    Optional<EmailVerificationEntity> findByUserIdForUpdate(@Param("userId") Long userId);
}
