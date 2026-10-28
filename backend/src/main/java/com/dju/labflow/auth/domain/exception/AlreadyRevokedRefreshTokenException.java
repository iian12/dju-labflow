package com.dju.labflow.auth.domain.exception;

public class AlreadyRevokedRefreshTokenException extends RefreshTokenException {
    public AlreadyRevokedRefreshTokenException() {
        super("Refresh token has already been revoked");
    }
}
