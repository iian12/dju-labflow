package com.dju.labflow.auth.infrastructure.persistence;

import com.dju.labflow.auth.domain.EmailVerification;
import java.util.Objects;

/** 인증 도메인과 저장 모델 사이의 변환 및 변경 가능한 필드 반영을 담당한다. */
public final class EmailVerificationMapper {
    private EmailVerificationMapper() {}

    public static EmailVerificationEntity toEntity(EmailVerification token) {
        Objects.requireNonNull(token, "Email verification token must not be null");
        return new EmailVerificationEntity(
                token.getUserId(),
                token.getCodeHash(),
                token.getExpiresAt(),
                token.getSentAt(),
                token.getWindowStartedAt(),
                token.getSendCount(),
                token.getFailures(),
                token.isUsed());
    }

    public static EmailVerification toDomain(EmailVerificationEntity entity) {
        Objects.requireNonNull(entity, "Email verification entity must not be null");
        return EmailVerification.restore(
                entity.getId(),
                entity.getCodeHash(),
                entity.getExpiresAt(),
                entity.getSentAt(),
                entity.getWindowStartedAt(),
                entity.getSendCount(),
                entity.getFailures(),
                entity.isUsed());
    }

    public static void updateEntity(EmailVerificationEntity entity, EmailVerification token) {
        Objects.requireNonNull(entity, "Email verification entity must not be null");
        Objects.requireNonNull(token, "Email verification token must not be null");
        if (!Objects.equals(entity.getId(), token.getUserId())) {
            throw new IllegalArgumentException("Cannot change verification owner");
        }
        entity.update(
                token.getCodeHash(),
                token.getExpiresAt(),
                token.getSentAt(),
                token.getWindowStartedAt(),
                token.getSendCount(),
                token.getFailures(),
                token.isUsed());
    }
}
