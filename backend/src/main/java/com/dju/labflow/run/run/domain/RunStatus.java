package com.dju.labflow.run.run.domain;

/** 실험 실행의 수명주기. 종료된 상태에서 다른 상태로 전환하지 않는다. */
public enum RunStatus {
    RUNNING,
    COMPLETED,
    FAILED
}
