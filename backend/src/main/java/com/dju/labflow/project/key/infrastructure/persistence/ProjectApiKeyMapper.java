package com.dju.labflow.project.key.infrastructure.persistence;

import com.dju.labflow.project.key.domain.ProjectApiKey;
import com.dju.labflow.project.key.domain.ProjectApiKeyId;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.user.domain.UserId;
import lombok.Getter;

/** 프로젝트 API 키 도메인과 JPA 저장 모델 사이의 명시적 필드 변환. */
@Getter
public class ProjectApiKeyMapper {

    private ProjectApiKeyMapper() {}

    public static ProjectApiKeyEntity toEntity(ProjectApiKey apiKey) {
        return ProjectApiKeyEntity.builder()
                .id(apiKey.getId().value())
                .projectId(apiKey.getProjectId().value())
                .createdBy(apiKey.getCreatedBy().value())
                .name(apiKey.getName())
                .keyPrefix(apiKey.getKeyPrefix())
                .keyHash(apiKey.getKeyHash())
                .status(apiKey.getStatus())
                .createdAt(apiKey.getCreatedAt())
                .revokedAt(apiKey.getRevokedAt())
                .lastUsedAt(apiKey.getLastUsedAt())
                .build();
    }

    public static ProjectApiKey toDomain(ProjectApiKeyEntity entity) {
        return ProjectApiKey.restore(
                ProjectApiKeyId.of(entity.getId()),
                ProjectId.of(entity.getProjectId()),
                UserId.of(entity.getCreatedBy()),
                entity.getName(),
                entity.getKeyPrefix(),
                entity.getKeyHash(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getRevokedAt(),
                entity.getLastUsedAt());
    }
}
