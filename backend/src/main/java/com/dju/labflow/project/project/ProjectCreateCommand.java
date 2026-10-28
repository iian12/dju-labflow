package com.dju.labflow.project.project;

import com.dju.labflow.project.project.domain.ProjectVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 프로젝트 생성 서비스 입력. 이름, 설명 길이 및 공개 범위의 필수값을 검증한다. */
public record ProjectCreateCommand(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 2000) String description,
        @NotNull ProjectVisibility visibility) {
}
