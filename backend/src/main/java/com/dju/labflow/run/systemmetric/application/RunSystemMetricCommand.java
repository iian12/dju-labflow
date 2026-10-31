package com.dju.labflow.run.systemmetric.application;

import com.dju.labflow.run.systemmetric.domain.GpuMetric;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

/** HTTP DTO와 분리한 실행 서비스 입력. 도메인에서도 필수 불변식을 검사한다. */
public record RunSystemMetricCommand(
        Double cpuUtilization,
        Long ramUsed,
        Long ramTotal,
        @Size(max = 64) List<@NotNull GpuMetric> gpus,
        @PositiveOrZero Double elapsedTime,
        Instant recordedAt) {}
