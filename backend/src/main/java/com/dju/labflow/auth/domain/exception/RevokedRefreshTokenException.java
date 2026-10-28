package com.dju.labflow.auth.domain.exception;

public class RevokedRefreshTokenException extends RefreshTokenException {
    public RevokedRefreshTokenException() {
        super("Refresh token has been revoked");
    }
}
