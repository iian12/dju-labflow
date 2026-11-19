package com.dju.labflow.run.run.domain;

/** 생존 신호의 관측 상태. 실험의 실행/종료 상태와 독립적이다. */
public enum RunConnectionStatus {
    WAITING,
    CONNECTED,
    DISCONNECTED,
    INACTIVE
}
