package com.dju.labflow.auth.domain.exception;

public abstract class RefreshTokenException extends RuntimeException {

    protected RefreshTokenException(String message) {
        super(message);
    }
}
