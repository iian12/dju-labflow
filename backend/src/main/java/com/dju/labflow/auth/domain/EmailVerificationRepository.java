package com.dju.labflow.auth.domain;

import java.util.Optional;

/** 서비스 트랜잭션 내에서 인증 상태를 저장하고 잠금 조회한다. */
public interface EmailVerificationRepository {
    /** 신규 INSERT 전용. 기존 상태는 update로 반영한다. */
    void save(EmailVerification token);

    /** 반환 후에도 서비스 트랜잭션이 종료될 때까지 쓰기 잠금을 유지한다. */
    Optional<EmailVerification> findForUpdate(Long userId);

    void update(EmailVerification token);
}
