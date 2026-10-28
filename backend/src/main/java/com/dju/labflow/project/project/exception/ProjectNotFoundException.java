package com.dju.labflow.project.project.exception;

/** 프로젝트 조회 실패를 잘못된 입력과 구분하는 예외. */
public class ProjectNotFoundException extends RuntimeException {
    public ProjectNotFoundException() {
        super("Project not found");
    }
}
