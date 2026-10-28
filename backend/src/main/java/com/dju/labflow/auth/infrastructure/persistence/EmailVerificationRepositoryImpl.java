package com.dju.labflow.auth.infrastructure.persistence;

import com.dju.labflow.auth.domain.EmailVerification;
import com.dju.labflow.auth.domain.EmailVerificationRepository;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 서비스 트랜잭션 안에서 신규 저장과 잠금 기반 변경을 수행한다. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class EmailVerificationRepositoryImpl implements EmailVerificationRepository {
    private final EmailVerificationJpaRepository jpaRepository;

    public EmailVerificationRepositoryImpl(EmailVerificationJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void save(EmailVerification token) {
        jpaRepository.save(EmailVerificationMapper.toEntity(token));
    }

    @Override
    public Optional<EmailVerification> findForUpdate(Long userId) {
        Objects.requireNonNull(userId, "User ID must not be null");
        return jpaRepository.findByUserIdForUpdate(userId).map(EmailVerificationMapper::toDomain);
    }

    @Override
    public void update(EmailVerification token) {
        Objects.requireNonNull(token, "Email verification token must not be null");
        EmailVerificationEntity entity =
                jpaRepository
                        .findByUserIdForUpdate(token.getUserId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Email verification does not exist"));
        EmailVerificationMapper.updateEntity(entity, token);
    }
}
