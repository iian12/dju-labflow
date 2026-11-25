package com.dju.labflow.project.key.exception;

/** DB 충돌 재시도 소진은 잘못된 키(401)와 구분한다. */
public final class ProjectApiKeyAuthenticationUnavailableException extends RuntimeException {
    public ProjectApiKeyAuthenticationUnavailableException(Throwable cause) {
        super("Project API key authentication is temporarily unavailable", cause);
    }
}
