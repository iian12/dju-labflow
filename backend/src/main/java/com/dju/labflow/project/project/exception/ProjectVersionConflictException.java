package com.dju.labflow.project.project.exception;

/** 클라이언트가 조회한 이후 프로젝트가 변경된 경우. */
public class ProjectVersionConflictException extends RuntimeException {
    public ProjectVersionConflictException() {
        super("Project changed; reload and retry");
    }
}
