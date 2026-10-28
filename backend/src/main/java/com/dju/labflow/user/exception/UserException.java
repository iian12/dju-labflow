package com.dju.labflow.user.exception;

/** 사용자 도메인의 예상 가능한 실패를 묶는 기반 예외. */
public abstract class UserException extends RuntimeException {
    protected UserException(String message) {
        super(message);
    }
}
