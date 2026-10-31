package com.dju.labflow.run.run.application;

import jakarta.validation.constraints.*;

/** HTTP DTO와 분리한 실행 서비스 입력. 도메인에서도 필수 불변식을 검사한다. */
public record RunFailCommand(@NotBlank @Size(max = 4000) String error) {}
