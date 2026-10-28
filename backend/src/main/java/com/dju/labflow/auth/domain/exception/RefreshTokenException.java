package com.dju.labflow.auth.domain.exception;

/** 리프레시 토큰 관련 인증 실패를 공통 HTTP 오류로 변환하기 위한 기반 예외. */
public abstract class RefreshTokenException extends RuntimeException {

    protected RefreshTokenException(String message) {
        super(message);
    }
}
