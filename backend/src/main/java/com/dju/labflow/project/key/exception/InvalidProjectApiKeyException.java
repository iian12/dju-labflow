package com.dju.labflow.project.key.exception;

import org.springframework.security.authentication.BadCredentialsException;

/** 외부에 키 존재 여부를 노출하지 않는 일반적인 API 키 인증 실패. */
public class InvalidProjectApiKeyException extends BadCredentialsException {
    public InvalidProjectApiKeyException(String message) {
        super("Invalid project API key");
    }
}
