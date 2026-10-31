package com.dju.labflow.sdk.presentation;

import java.time.Instant;
import java.util.List;

public record SystemMetricCreateRequest(
        Double elapsedTime, SystemMetricData system, Instant recordedAt) {

    public record SystemMetricData(
            Double cpuUtilization, Long ramUsed, Long ramTotal, List<GpuMetricData> gpus) {
        public record GpuMetricData(
                Integer index, Double utilization, Long memoryUsed, Long memoryTotal) {}
    }
}
