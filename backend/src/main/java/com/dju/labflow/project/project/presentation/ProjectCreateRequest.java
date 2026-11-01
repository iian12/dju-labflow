package com.dju.labflow.project.project.presentation;

import com.dju.labflow.project.project.application.ProjectCreateCommand;
import com.dju.labflow.project.project.domain.ProjectVisibility;
import jakarta.validation.constraints.*;

/** 개인 프로젝트 생성 요청. 소유자는 인증된 사용자다. */
public record ProjectCreateRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 2000) String description,
        @NotNull ProjectVisibility visibility) {
    public ProjectCreateCommand toCommand() {
        return new ProjectCreateCommand(name, description, visibility);
    }
}
