package com.dju.labflow.auth.domain.exception;

/** 만료 시각에 도달한 리프레시 토큰의 사용을 차단한다. */
public class ExpiredRefreshTokenException extends RefreshTokenException {
    public ExpiredRefreshTokenException() {
        super("Expired refresh token");
    }
}
