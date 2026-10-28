package com.dju.labflow.auth.domain.exception;

/** 폐기 등 저장 작업의 대상 리프레시 토큰이 존재하지 않음을 나타낸다. */
public class RefreshTokenNotFoundException extends RefreshTokenException {
    public RefreshTokenNotFoundException() {
        super("Refresh token not found");
    }
}
