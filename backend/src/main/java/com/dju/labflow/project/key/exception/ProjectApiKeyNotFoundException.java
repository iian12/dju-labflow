package com.dju.labflow.project.key.exception;

/** 관리 작업의 대상 API 키가 존재하지 않는 경우. SDK 인증 실패와 구분한다. */
public class ProjectApiKeyNotFoundException extends RuntimeException {
    public ProjectApiKeyNotFoundException() {
        super("Project API key not found");
    }
}
