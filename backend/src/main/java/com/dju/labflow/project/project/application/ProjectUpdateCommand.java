package com.dju.labflow.project.project.application;

import com.dju.labflow.project.project.domain.ProjectVisibility;
import jakarta.validation.constraints.*;

/** 전체 편집 정보와 클라이언트가 마지막으로 조회한 버전. */
public record ProjectUpdateCommand(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 2000) String description,
        @NotNull ProjectVisibility visibility,
        @NotNull @PositiveOrZero Long version) {}
