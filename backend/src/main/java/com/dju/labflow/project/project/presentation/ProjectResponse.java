package com.dju.labflow.project.project.presentation;

import com.dju.labflow.project.project.domain.Project;
import com.dju.labflow.project.project.domain.ProjectVisibility;
import java.time.Instant;

/** 프로젝트 정보와 수정/삭제에 필요한 조회 버전. 키나 실행 데이터는 포함하지 않는다. */
public record ProjectResponse(
        Long projectId,
        String name,
        String description,
        Long createdBy,
        ProjectVisibility visibility,
        Instant createdAt,
        Instant updatedAt,
        Long version) {
    public static ProjectResponse from(Project project) {
        return new ProjectResponse(
                project.getId().value(),
                project.getName(),
                project.getDescription(),
                project.getCreatedBy().value(),
                project.getVisibility(),
                project.getCreatedAt(),
                project.getUpdatedAt(),
                project.getVersion());
    }
}
