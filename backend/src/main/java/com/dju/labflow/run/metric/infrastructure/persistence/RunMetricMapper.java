package com.dju.labflow.run.metric.infrastructure.persistence;

import com.dju.labflow.run.metric.domain.*;
import com.dju.labflow.run.run.domain.RunId;

/** RunMetric의 도메인과 엔티티 간 변환. */
public final class RunMetricMapper {
    private RunMetricMapper() {}

    public static RunMetricEntity toEntity(RunMetric domain) {
        return RunMetricEntity.builder()
                .id(domain.getId().value())
                .runId(domain.getRunId().value())
                .key(domain.getKey())
                .value(domain.getValue())
                .epoch(domain.getEpoch())
                .step(domain.getStep())
                .elapsedTime(domain.getElapsedTime())
                .recordedAt(domain.getRecordedAt())
                .build();
    }

    public static RunMetric toDomain(RunMetricEntity entity) {
        return RunMetric.restore(
                RunMetricId.of(entity.getId()),
                RunId.of(entity.getRunId()),
                entity.getKey(),
                entity.getValue(),
                entity.getEpoch(),
                entity.getStep(),
                entity.getElapsedTime(),
                entity.getRecordedAt());
    }
}
