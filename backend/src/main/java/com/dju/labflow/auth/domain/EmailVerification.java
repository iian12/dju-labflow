package com.dju.labflow.auth.domain;

import java.time.Instant;
import lombok.Getter;

/** 사용자당 하나의 인증 코드와 재발송/검증 제한을 관리한다. */
@Getter
public class EmailVerification {
    private final Long userId;
    private String codeHash;
    private Instant expiresAt;
    private Instant sentAt;
    private Instant windowStartedAt;
    private int sendCount;
    private int failures;
    private boolean used;

    private EmailVerification(
            Long userId,
            String codeHash,
            Instant expiresAt,
            Instant sentAt,
            Instant windowStartedAt,
            int sendCount,
            int failures,
            boolean used) {
        this.userId = userId;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.sentAt = sentAt;
        this.windowStartedAt = windowStartedAt;
        this.sendCount = sendCount;
        this.failures = failures;
        this.used = used;
    }

    /** 저장된 인증 상태를 복원한다. 신규 발급 정책은 issue에서 적용한다. */
    public static EmailVerification restore(
            Long userId,
            String codeHash,
            Instant expiresAt,
            Instant sentAt,
            Instant windowStartedAt,
            int sendCount,
            int failures,
            boolean used) {
        return new EmailVerification(
                userId, codeHash, expiresAt, sentAt, windowStartedAt, sendCount, failures, used);
    }

    public static EmailVerification issue(Long userId, String hash, Instant now) {
        return new EmailVerification(userId, hash, now.plusSeconds(600), now, now, 1, 0, false);
    }

    public boolean canResend(Instant now) {
        return !used
                && !now.isBefore(sentAt.plusSeconds(60))
                && (!now.isBefore(windowStartedAt.plusSeconds(3600)) || sendCount < 5);
    }

    public void resend(String hash, Instant now) {
        if (!canResend(now)) throw new IllegalStateException("Resend limit reached");
        if (!now.isBefore(windowStartedAt.plusSeconds(3600))) {
            windowStartedAt = now;
            sendCount = 0;
        }
        codeHash = hash;
        expiresAt = now.plusSeconds(600);
        sentAt = now;
        sendCount++;
        failures = 0;
    }

    public boolean usable(Instant now) {
        return !used && failures < 5 && now.isBefore(expiresAt);
    }

    public void fail() {
        failures++;
    }

    public void consume() {
        used = true;
    }
}
