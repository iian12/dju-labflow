package com.dju.labflow.project.project.presentation;

import com.dju.labflow.project.project.application.ProjectUpdateCommand;
import com.dju.labflow.project.project.domain.ProjectVisibility;
import jakarta.validation.constraints.*;

/** 전체 수정 요청. 상세 조회에서 받은 version을 함께 전송한다. */
public record ProjectUpdateRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 2000) String description,
        @NotNull ProjectVisibility visibility,
        @NotNull @PositiveOrZero Long version) {
    public ProjectUpdateCommand toCommand() {
        return new ProjectUpdateCommand(name, description, visibility, version);
    }
}
