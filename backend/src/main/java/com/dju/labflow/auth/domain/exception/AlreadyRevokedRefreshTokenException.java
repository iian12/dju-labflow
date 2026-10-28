package com.dju.labflow.auth.domain.exception;

/** 이미 폐기된 토큰을 다시 폐기하려는 상태 전이를 나타낸다. */
public class AlreadyRevokedRefreshTokenException extends RefreshTokenException {
    public AlreadyRevokedRefreshTokenException() {
        super("Refresh token has already been revoked");
    }
}
