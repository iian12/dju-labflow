package com.dju.labflow.auth.domain.exception;

public class RefreshTokenNotFoundException extends RefreshTokenException {
    public RefreshTokenNotFoundException() {
        super("Refresh token not found");
    }
}
