package com.dju.labflow.project.key.exception;

import org.springframework.security.authentication.BadCredentialsException;

public class InvalidProjectApiKeyException extends BadCredentialsException {
    public InvalidProjectApiKeyException(String message) {
        super("Invalid project API key");
    }
}
