package com.dju.labflow.run.run.infrastructure.persistence;

import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.run.run.domain.Run;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.user.domain.UserId;

/** 실행의 식별자, 메타데이터, 시간 및 버전을 보존하는 변환. */
public final class RunMapper {
    private RunMapper() {}

    public static RunEntity toEntity(Run run) {
        return RunEntity.builder()
                .id(run.getId().value())
                .projectId(run.getProjectId().value())
                .createdBy(run.getCreatedBy().value())
                .name(run.getName())
                .metadata(run.getMetadata())
                .status(run.getStatus())
                .startedAt(run.getStartedAt())
                .endedAt(run.getEndedAt())
                .error(run.getError())
                .build();
    }

    public static Run toDomain(RunEntity entity) {
        return Run.restore(
                RunId.of(entity.getId()),
                ProjectId.of(entity.getProjectId()),
                UserId.of(entity.getCreatedBy()),
                entity.getName(),
                entity.getMetadata(),
                entity.getStatus(),
                entity.getStartedAt(),
                entity.getEndedAt(),
                entity.getError(),
                entity.getVersion());
    }
}
