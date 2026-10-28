package com.dju.labflow.auth.domain;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** 사용자당 하나의 인증 코드와 재발송/검증 제한을 관리한다. */
@Getter
@AllArgsConstructor
public class EmailVerificationToken {
    private final Long userId;
    private String codeHash;
    private Instant expiresAt;
    private Instant sentAt;
    private Instant windowStartedAt;
    private int sendCount;
    private int failures;
    private boolean used;

    public static EmailVerificationToken issue(Long userId, String hash, Instant now) {
        return new EmailVerificationToken(
                userId, hash, now.plusSeconds(600), now, now, 1, 0, false);
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
