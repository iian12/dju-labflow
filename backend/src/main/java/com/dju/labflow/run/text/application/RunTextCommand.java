package com.dju.labflow.run.text.application;

import jakarta.validation.constraints.*;
import java.time.Instant;

/** HTTP DTO와 분리한 실행 서비스 입력. 도메인에서도 필수 불변식을 검사한다. */
public record RunTextCommand(
        @NotBlank @Size(max = 100) String key,
        @NotNull @Size(max = 65536) String text,
        @PositiveOrZero Long step,
        Instant recordedAt) {}
