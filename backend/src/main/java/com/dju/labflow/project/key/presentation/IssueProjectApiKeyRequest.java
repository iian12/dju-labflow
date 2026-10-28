package com.dju.labflow.project.key.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.dju.labflow.project.key.application.IssueProjectApiKeyCommand;

/** 키 발급 요청. 이름의 공백과 길이 제약은 MVC와 서비스에서 모두 검증한다. */
public record IssueProjectApiKeyRequest(
        @NotBlank @Size(max = 100)
        @Schema(description = "사용 목적을 식별할 키 이름", example = "training-server") String name
) {
    public IssueProjectApiKeyCommand toCommand() {
        return new IssueProjectApiKeyCommand(name);
    }
}
