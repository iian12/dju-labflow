package com.dju.labflow.project.project;

import com.dju.labflow.project.project.domain.ProjectVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 향후 프로젝트 생성 HTTP API에 사용할 요청 DTO. 서비스 명령 객체로 변환한다. */
public record ProjectCreateRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 2000) String description,
        @NotNull ProjectVisibility visibility) {
    public ProjectCreateCommand toCommand() {
        return new ProjectCreateCommand(name, description, visibility);
    }
}
