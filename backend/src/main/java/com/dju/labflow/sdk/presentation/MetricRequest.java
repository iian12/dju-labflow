package com.dju.labflow.sdk.presentation;

import com.dju.labflow.run.metric.application.RunMetricCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.Map;
import tools.jackson.databind.annotation.JsonDeserialize;

/** 수치형 메트릭 배치. 숫자 형태의 문자열도 자동 변환하지 않고 거부한다. */
public record MetricRequest(
        @PositiveOrZero Integer epoch,
        @PositiveOrZero Long step,
        @PositiveOrZero @Schema(description = "실행 시작 후 경과 시간(초)") Double elapsedTime,
        @NotEmpty @Size(max = 100) @JsonDeserialize(contentUsing = MetricValueDeserializer.class)
                Map<@NotBlank @Size(max = 100) String, @NotNull Double> metrics,
        @Schema(description = "클라이언트 기록 시각. 생략하면 서버 시각을 사용합니다.") Instant recordedAt) {
    public RunMetricCommand toCommand() {
        return new RunMetricCommand(epoch, step, elapsedTime, metrics, recordedAt);
    }
}
