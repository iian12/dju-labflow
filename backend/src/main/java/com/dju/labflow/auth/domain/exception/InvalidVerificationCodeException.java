package com.dju.labflow.auth.domain.exception;

/** 이 예외만 롤백에서 제외하여 실패 횟수를 유지한다. */
public class InvalidVerificationCodeException extends IllegalArgumentException {
    public InvalidVerificationCodeException() {
        super("Invalid or expired verification code");
    }
}
