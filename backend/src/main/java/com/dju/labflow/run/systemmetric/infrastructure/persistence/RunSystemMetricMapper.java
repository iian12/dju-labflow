package com.dju.labflow.run.systemmetric.infrastructure.persistence;

import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.run.systemmetric.domain.*;

/** RunSystemMetric의 도메인과 엔티티 간 변환. */
public final class RunSystemMetricMapper {
    private RunSystemMetricMapper() {}

    public static RunSystemMetricEntity toEntity(RunSystemMetric domain) {
        return RunSystemMetricEntity.builder()
                .id(domain.getId().value())
                .runId(domain.getRunId().value())
                .cpuUtilization(domain.getCpuUtilization())
                .ramUsed(domain.getRamUsed())
                .ramTotal(domain.getRamTotal())
                .gpus(
                        domain.getGpus().stream()
                                .map(
                                        g ->
                                                new GpuMetricEmbeddable(
                                                        g.index(),
                                                        g.utilization(),
                                                        g.memoryUsed(),
                                                        g.memoryTotal()))
                                .toList())
                .elapsedTime(domain.getElapsedTime())
                .recordedAt(domain.getRecordedAt())
                .build();
    }

    public static RunSystemMetric toDomain(RunSystemMetricEntity entity) {
        return RunSystemMetric.restore(
                RunSystemMetricId.of(entity.getId()),
                RunId.of(entity.getRunId()),
                entity.getCpuUtilization(),
                entity.getRamUsed(),
                entity.getRamTotal(),
                entity.getGpus().stream()
                        .map(
                                g ->
                                        new GpuMetric(
                                                g.getGpuIndex(),
                                                g.getUtilization(),
                                                g.getMemoryUsed(),
                                                g.getMemoryTotal()))
                        .toList(),
                entity.getElapsedTime(),
                entity.getRecordedAt());
    }
}
