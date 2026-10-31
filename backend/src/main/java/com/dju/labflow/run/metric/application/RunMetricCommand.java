package com.dju.labflow.run.metric.application;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.Map;

/** HTTP DTO와 분리한 실행 서비스 입력. 도메인에서도 필수 불변식을 검사한다. */
public record RunMetricCommand(
        @PositiveOrZero Integer epoch,
        @PositiveOrZero Long step,
        @PositiveOrZero Double elapsedTime,
        @NotEmpty @Size(max = 100) Map<@NotBlank @Size(max = 100) String, @NotNull Double> metrics,
        Instant recordedAt) {}
