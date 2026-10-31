package com.dju.labflow.run.systemmetric.domain;

import com.dju.labflow.global.validation.InputValidation;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.run.run.domain.RunValidation;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import lombok.Getter;

/** SDK가 수집한 CPU/RAM/GPU 스냅샷. 사용률은 백분율, 메모리는 바이트 단위다. */
@Getter
public class RunSystemMetric {
    private final RunSystemMetricId id;
    private final RunId runId;
    private final Double cpuUtilization;
    private final Long ramUsed;
    private final Long ramTotal;
    private final List<GpuMetric> gpus;
    private final Double elapsedTime;
    private final Instant recordedAt;

    private RunSystemMetric(
            RunSystemMetricId id,
            RunId runId,
            Double cpuUtilization,
            Long ramUsed,
            Long ramTotal,
            List<GpuMetric> gpus,
            Double elapsedTime,
            Instant recordedAt) {
        this.id = InputValidation.required(id, "System metric ID");
        this.runId = InputValidation.required(runId, "Run ID");
        this.cpuUtilization = RunValidation.percentage(cpuUtilization, "CPU utilization");
        RunValidation.memory(ramUsed, ramTotal);
        this.ramUsed = ramUsed;
        this.ramTotal = ramTotal;
        if (gpus != null && gpus.size() > 64)
            throw new IllegalArgumentException("GPU metrics exceed 64 entries");
        if (gpus != null) gpus.forEach(gpu -> InputValidation.required(gpu, "GPU metric"));
        this.gpus =
                gpus == null
                        ? List.of()
                        : gpus.stream().sorted(Comparator.comparing(GpuMetric::index)).toList();
        if (this.gpus.stream().map(GpuMetric::index).distinct().count() != this.gpus.size())
            throw new IllegalArgumentException("GPU indices must be unique");
        if (cpuUtilization == null && ramUsed == null && ramTotal == null && this.gpus.isEmpty())
            throw new IllegalArgumentException("System metric must contain a measurement");
        this.elapsedTime = RunValidation.elapsed(elapsedTime);
        this.recordedAt = InputValidation.required(recordedAt, "Recording time");
    }

    public static RunSystemMetric create(
            RunSystemMetricId id,
            RunId runId,
            Double cpuUtilization,
            Long ramUsed,
            Long ramTotal,
            List<GpuMetric> gpus,
            Double elapsedTime,
            Instant recordedAt) {
        return new RunSystemMetric(
                id, runId, cpuUtilization, ramUsed, ramTotal, gpus, elapsedTime, recordedAt);
    }

    public static RunSystemMetric restore(
            RunSystemMetricId id,
            RunId runId,
            Double cpuUtilization,
            Long ramUsed,
            Long ramTotal,
            List<GpuMetric> gpus,
            Double elapsedTime,
            Instant recordedAt) {
        return new RunSystemMetric(
                id, runId, cpuUtilization, ramUsed, ramTotal, gpus, elapsedTime, recordedAt);
    }
}
