package com.dju.labflow.run.log.infrastructure.persistence;

import com.dju.labflow.run.log.domain.*;
import com.dju.labflow.run.run.domain.RunId;

/** RunLog의 도메인과 엔티티 간 변환. */
public final class RunLogMapper {
    private RunLogMapper() {}

    public static RunLogEntity toEntity(RunLog domain) {
        return RunLogEntity.builder()
                .id(domain.getId().value())
                .runId(domain.getRunId().value())
                .message(domain.getMessage())
                .level(domain.getLevel())
                .source(domain.getSource())
                .recordedAt(domain.getRecordedAt())
                .build();
    }

    public static RunLog toDomain(RunLogEntity entity) {
        return RunLog.restore(
                RunLogId.of(entity.getId()),
                RunId.of(entity.getRunId()),
                entity.getMessage(),
                entity.getLevel(),
                entity.getSource(),
                entity.getRecordedAt());
    }
}
