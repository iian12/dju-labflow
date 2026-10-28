package com.dju.labflow.auth.application;

import com.dju.labflow.user.domain.AccountStatus;
import com.dju.labflow.user.domain.User;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;

/** 토큰 갱신과 기존 토큰 사용에 동일한 ACTIVE 계정 정책을 적용한다. */
public final class AccountAccessPolicy {
    private AccountAccessPolicy() {}

    public static void requireActive(User user) {
        if (user.getAccountStatus() == AccountStatus.BLOCKED) {
            throw new LockedException("Account is blocked");
        }
        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new DisabledException("Account verification is required");
        }
    }
}
