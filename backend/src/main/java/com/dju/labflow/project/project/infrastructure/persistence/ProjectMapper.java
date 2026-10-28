package com.dju.labflow.project.project.infrastructure.persistence;

import com.dju.labflow.project.project.domain.Project;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.user.domain.UserId;

public class ProjectMapper {

    private ProjectMapper() {

    }

    public static ProjectEntity toEntity(Project domain) {
        return ProjectEntity.builder()
                .id(domain.getId().value())
                .name(domain.getName())
                .description(domain.getDescription())
                .createdBy(domain.getCreatedBy().value())
                .visibility(domain.getVisibility())
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }

    public static Project toDomain(ProjectEntity entity) {
        return Project.restore(
                ProjectId.of(entity.getId()),
                entity.getName(),
                entity.getDescription(),
                UserId.of(entity.getCreatedBy()),
                entity.getVisibility(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
