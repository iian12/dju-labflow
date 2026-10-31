package com.dju.labflow.run.metric.domain;

import com.dju.labflow.global.validation.InputValidation;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.run.run.domain.RunValidation;
import java.time.Instant;
import lombok.Getter;

/** 한 실행의 숫자 지표 한 점. 문자열과 비유한 수는 허용하지 않는다. */
@Getter
public class RunMetric {
    private final RunMetricId id;
    private final RunId runId;
    private final String key;
    private final Double value;
    private final Integer epoch;
    private final Long step;
    private final Double elapsedTime;
    private final Instant recordedAt;

    private RunMetric(
            RunMetricId id,
            RunId runId,
            String key,
            Double value,
            Integer epoch,
            Long step,
            Double elapsedTime,
            Instant recordedAt) {
        this.id = InputValidation.required(id, "RunMetric ID");
        this.runId = InputValidation.required(runId, "Run ID");
        this.key = InputValidation.text(key, "Metric key", 100);
        this.value = RunValidation.finite(value, "Metric value");
        this.epoch = RunValidation.nonNegative(epoch, "Epoch");
        this.step = RunValidation.nonNegative(step, "Step");
        this.elapsedTime = RunValidation.elapsed(elapsedTime);
        this.recordedAt = InputValidation.required(recordedAt, "Recording time");
    }

    public static RunMetric create(
            RunMetricId id,
            RunId runId,
            String key,
            Double value,
            Integer epoch,
            Long step,
            Double elapsedTime,
            Instant recordedAt) {
        return new RunMetric(id, runId, key, value, epoch, step, elapsedTime, recordedAt);
    }

    public static RunMetric restore(
            RunMetricId id,
            RunId runId,
            String key,
            Double value,
            Integer epoch,
            Long step,
            Double elapsedTime,
            Instant recordedAt) {
        return new RunMetric(id, runId, key, value, epoch, step, elapsedTime, recordedAt);
    }
}
