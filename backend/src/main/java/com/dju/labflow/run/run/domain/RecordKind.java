package com.dju.labflow.run.run.domain;

/** 파라미터와 달리 페이지 이동이 필요한 추가 전용 시계열 종류. */
public enum RecordKind {
    METRICS("metrics"),
    LOGS("logs"),
    TEXTS("texts"),
    SYSTEM_METRICS("system-metrics");

    private final String path;

    RecordKind(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }

    public static RecordKind fromPath(String path) {
        for (RecordKind kind : values()) if (kind.path.equals(path)) return kind;
        throw new IllegalArgumentException("Unknown record kind");
    }
}
