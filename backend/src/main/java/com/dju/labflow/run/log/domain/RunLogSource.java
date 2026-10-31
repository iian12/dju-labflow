package com.dju.labflow.run.log.domain;

/** 직접 기록, 캡처한 콘솔 출력, SDK 자체 이벤트의 구분. */
public enum RunLogSource {
    USER,
    STDOUT,
    STDERR,
    SDK
}
