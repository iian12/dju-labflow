package com.dju.labflow.run.run.application;

import jakarta.validation.constraints.*;
import java.util.Map;

/** HTTP DTO와 분리한 실행 서비스 입력. 도메인에서도 필수 불변식을 검사한다. */
public record RunCreateCommand(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 100)
                Map<@NotBlank @Size(max = 100) String, @NotNull @Size(max = 2000) String>
                        metadata) {}
