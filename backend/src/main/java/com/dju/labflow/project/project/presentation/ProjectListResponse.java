package com.dju.labflow.project.project.presentation;

import com.dju.labflow.project.project.domain.ProjectPage;
import java.util.List;

/** 개인 프로젝트 페이지. createdAt/ID 내림차순이다. */
public record ProjectListResponse(
        List<ProjectResponse> items, int page, int size, long totalElements, long totalPages) {
    public static ProjectListResponse from(ProjectPage result) {
        return new ProjectListResponse(
                result.items().stream().map(ProjectResponse::from).toList(),
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages());
    }
}
