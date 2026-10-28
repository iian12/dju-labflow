package com.dju.labflow.auth.domain.exception;

public class ExpiredRefreshTokenException extends RefreshTokenException {
    public ExpiredRefreshTokenException() {
        super("Expired refresh token");
    }
}
