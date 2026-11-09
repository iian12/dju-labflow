package com.dju.labflow.auth.domain.exception;

import org.springframework.security.core.AuthenticationException;

/** 올바른 비밀번호를 확인한 미인증 계정의 이메일 인증 재개를 요청한다. */
public class EmailVerificationRequiredException extends AuthenticationException {
    public EmailVerificationRequiredException() {
        super("Email verification is required");
    }
}
