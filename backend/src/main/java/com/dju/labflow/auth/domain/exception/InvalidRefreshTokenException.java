package com.dju.labflow.auth.domain.exception;

/** 제공된 리프레시 토큰으로 유효한 인증 대상을 찾지 못했음을 나타낸다. */
public class InvalidRefreshTokenException extends RefreshTokenException {
    public InvalidRefreshTokenException() {
        super("Invalid refresh token");
    }
}
