package com.dju.labflow.project.project.presentation;

import com.dju.labflow.project.project.domain.ProjectId;

/** 생성한 프로젝트 ID. 상세 조회 URL은 Location 헤더로 반환한다. */
public record ProjectCreateResponse(Long projectId) {
    public static ProjectCreateResponse from(ProjectId id) {
        return new ProjectCreateResponse(id.value());
    }
}
