package com.dju.labflow.project.project.application;

import com.dju.labflow.project.project.domain.ProjectVisibility;
import jakarta.validation.constraints.*;

/** 프로젝트 생성 명령. 소유자는 인증 정보에서 결정한다. */
public record ProjectCreateCommand(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 2000) String description,
        @NotNull ProjectVisibility visibility) {}
