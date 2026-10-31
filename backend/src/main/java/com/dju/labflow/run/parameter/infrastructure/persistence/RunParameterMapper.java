package com.dju.labflow.run.parameter.infrastructure.persistence;

import com.dju.labflow.run.parameter.domain.*;
import com.dju.labflow.run.run.domain.RunId;

/** RunParameter의 도메인과 엔티티 간 변환. */
public final class RunParameterMapper {
    private RunParameterMapper() {}

    public static RunParameterEntity toEntity(RunParameter domain) {
        return RunParameterEntity.builder()
                .id(domain.getId().value())
                .runId(domain.getRunId().value())
                .key(domain.getKey())
                .value(domain.getValue())
                .createdAt(domain.getCreatedAt())
                .build();
    }

    public static RunParameter toDomain(RunParameterEntity entity) {
        return RunParameter.restore(
                RunParameterId.of(entity.getId()),
                RunId.of(entity.getRunId()),
                entity.getKey(),
                entity.getValue(),
                entity.getCreatedAt());
    }
}
