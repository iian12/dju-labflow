package com.dju.labflow.run.run.domain;

/** before/최신 조회는 역방향으로 제한 조회하고 응답에서는 다시 오름차순으로 정렬한다. */
public record RecordPageQuery(int limit, RecordPosition position, boolean backwards) {
    public RecordPageQuery {
        RunValidation.limit(limit);
    }
}
