package com.dju.labflow.auth.domain.exception;

/** 이미 폐기된 리프레시 토큰의 재사용을 차단한다. */
public class RevokedRefreshTokenException extends RefreshTokenException {
    public RevokedRefreshTokenException() {
        super("Refresh token has been revoked");
    }
}
