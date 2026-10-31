package com.dju.labflow.sdk.presentation;

import com.dju.labflow.run.systemmetric.application.RunSystemMetricCommand;
import com.dju.labflow.run.systemmetric.domain.GpuMetric;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

/** CPU/GPU 사용률은 백분율, 메모리 수치는 바이트로 받는다. GPU 없는 환경도 허용한다. */
public record SystemMetricCreateRequest(
        @PositiveOrZero @Schema(description = "실행 시작 후 경과 시간(초)") Double elapsedTime,
        @NotNull @Valid SystemMetricData system,
        Instant recordedAt) {

    public RunSystemMetricCommand toCommand() {
        return new RunSystemMetricCommand(
                system.cpuUtilization(),
                system.ramUsed(),
                system.ramTotal(),
                system.gpus() == null
                        ? null
                        : system.gpus().stream()
                                .map(SystemMetricData.GpuMetricData::toDomain)
                                .toList(),
                elapsedTime,
                recordedAt);
    }

    /** 수집하지 못한 개별 수치는 생략할 수 있다. 적어도 하나의 측정값은 필요하다. */
    public record SystemMetricData(
            @DecimalMin("0") @DecimalMax("100") Double cpuUtilization,
            @PositiveOrZero Long ramUsed,
            @PositiveOrZero Long ramTotal,
            @Size(max = 64) List<@NotNull @Valid GpuMetricData> gpus) {
        /** GPU별 측정값. 같은 스냅샷 안에서 index는 중복될 수 없다. */
        public record GpuMetricData(
                @NotNull @PositiveOrZero Integer index,
                @DecimalMin("0") @DecimalMax("100") Double utilization,
                @PositiveOrZero Long memoryUsed,
                @PositiveOrZero Long memoryTotal) {
            public GpuMetric toDomain() {
                return new GpuMetric(index, utilization, memoryUsed, memoryTotal);
            }
        }
    }
}
