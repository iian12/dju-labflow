package com.dju.labflow.run.run.domain;

import java.time.Instant;

/** 동일 기록 시각에서도 누락/중복 없이 이동하기 위한 복합 정렬 위치. */
public record RecordPosition(Instant recordedAt, long id) {
    public RecordPosition {
        if (recordedAt == null
                || id < 1
                || recordedAt.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                || recordedAt.isAfter(Instant.parse("9999-12-31T23:59:59.999999999Z")))
            throw new IllegalArgumentException("Invalid record position");
    }
}
