package com.dju.labflow.project.key.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** HTTP 요청 DTO와 분리된 API 키 발급 명령. 다른 진입점에서도 같은 검증을 적용한다. */
public record IssueProjectApiKeyCommand(@NotBlank @Size(max = 100) String name) {}
