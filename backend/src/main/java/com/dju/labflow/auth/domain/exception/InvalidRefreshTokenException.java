package com.dju.labflow.auth.domain.exception;

public class InvalidRefreshTokenException extends RefreshTokenException {
    public InvalidRefreshTokenException() {
        super("Invalid refresh token");
    }
}
