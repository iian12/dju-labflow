package com.dju.labflow.user.domain;

/** 미인증, 활성, 차단 상태. 인증과 토큰 갱신은 ACTIVE에만 허용한다. */
public enum AccountStatus {
    PENDING_VERIFICATION,
    ACTIVE,
    BLOCKED
}
