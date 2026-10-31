package com.dju.labflow.run.text.infrastructure.persistence;

import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.run.text.domain.*;

/** RunText의 도메인과 엔티티 간 변환. */
public final class RunTextMapper {
    private RunTextMapper() {}

    public static RunTextEntity toEntity(RunText domain) {
        return RunTextEntity.builder()
                .id(domain.getId().value())
                .runId(domain.getRunId().value())
                .key(domain.getKey())
                .text(domain.getText())
                .step(domain.getStep())
                .recordedAt(domain.getRecordedAt())
                .build();
    }

    public static RunText toDomain(RunTextEntity entity) {
        return RunText.restore(
                RunTextId.of(entity.getId()),
                RunId.of(entity.getRunId()),
                entity.getKey(),
                entity.getText(),
                entity.getStep(),
                entity.getRecordedAt());
    }
}
