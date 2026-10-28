package com.dju.labflow.auth.infrastructure.persistence;

import com.dju.labflow.global.id.AssignedIdEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;

/** 인증 원문 없이 HMAC과 시도 제한 상태만 저장한다. */
@Entity
@Getter
@Table(name = "email_verifications")
@AttributeOverride(
        name = "id",
        column = @Column(name = "user_id", nullable = false, updatable = false))
public class EmailVerificationEntity extends AssignedIdEntity {

    @Column(nullable = false, length = 64)
    private String codeHash;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private Instant sentAt;

    @Column(nullable = false)
    private Instant windowStartedAt;

    @Column(nullable = false)
    private int sendCount;

    @Column(nullable = false)
    private int failures;

    @Column(nullable = false)
    private boolean used;

    protected EmailVerificationEntity() {}

    EmailVerificationEntity(
            Long userId,
            String codeHash,
            Instant expiresAt,
            Instant sentAt,
            Instant windowStartedAt,
            int sendCount,
            int failures,
            boolean used) {
        this.id = userId;
        update(codeHash, expiresAt, sentAt, windowStartedAt, sendCount, failures, used);
    }

    void update(
            String codeHash,
            Instant expiresAt,
            Instant sentAt,
            Instant windowStartedAt,
            int sendCount,
            int failures,
            boolean used) {
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.sentAt = sentAt;
        this.windowStartedAt = windowStartedAt;
        this.sendCount = sendCount;
        this.failures = failures;
        this.used = used;
    }
}
