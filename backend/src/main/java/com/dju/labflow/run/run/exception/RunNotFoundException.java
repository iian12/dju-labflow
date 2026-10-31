package com.dju.labflow.run.run.exception;

/** 요청한 실행을 찾을 수 없는 경우. */
public class RunNotFoundException extends RuntimeException {
    public RunNotFoundException() {
        super("Run not found");
    }
}
