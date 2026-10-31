package com.dju.labflow.run.run.exception;

/** 실행 상태 또는 이미 저장한 파라미터와 요청이 충돌한 경우. */
public class RunStateConflictException extends IllegalStateException {
    public RunStateConflictException(String message) {
        super(message);
    }
}
