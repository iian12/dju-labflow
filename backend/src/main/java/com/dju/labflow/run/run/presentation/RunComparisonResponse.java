package com.dju.labflow.run.run.presentation;

import com.dju.labflow.run.metric.domain.RunMetric;
import com.dju.labflow.run.run.application.RunComparison;
import com.dju.labflow.run.run.domain.Run;
import com.dju.labflow.run.run.domain.RunStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 비교 화면에 필요한 수치와 샘플링 여부를 명시하는 웹 응답. */
public record RunComparisonResponse(
        String metricKey,
        Long baselineRunId,
        Long commonStep,
        List<String> availableMetrics,
        List<ComparedRun> runs,
        List<RunComparison.ParameterComparison> parameters,
        int maxPoints,
        boolean differencesOnly) {
    public record MetricPoint(
            Long id,
            Long step,
            Double value,
            Integer epoch,
            Double elapsedTime,
            Instant recordedAt) {
        static MetricPoint from(RunMetric metric) {
            return metric == null
                    ? null
                    : new MetricPoint(
                            metric.getId().value(),
                            metric.getStep(),
                            metric.getValue(),
                            metric.getEpoch(),
                            metric.getElapsedTime(),
                            metric.getRecordedAt());
        }
    }

    public record Series(List<MetricPoint> points, long totalPoints, boolean sampled) {}

    public record ComparedRun(
            Long runId,
            String name,
            RunStatus status,
            Instant startedAt,
            Instant endedAt,
            Long lastStep,
            MetricPoint latestMetric,
            MetricPoint commonStepMetric,
            BigDecimal deltaFromBaseline,
            Series series) {}

    public static RunComparisonResponse from(RunComparison comparison) {
        return new RunComparisonResponse(
                comparison.metricKey(),
                comparison.baselineRunId().value(),
                comparison.commonStep(),
                comparison.availableMetrics(),
                comparison.runs().stream()
                        .map(
                                item -> {
                                    Run run = item.run();
                                    List<RunMetric> points = item.series().points();
                                    return new ComparedRun(
                                            run.getId().value(),
                                            run.getName(),
                                            run.getStatus(),
                                            run.getStartedAt(),
                                            run.getEndedAt(),
                                            points.isEmpty() ? null : points.getLast().getStep(),
                                            MetricPoint.from(item.latestMetric()),
                                            MetricPoint.from(item.commonStepMetric()),
                                            item.deltaFromBaseline(),
                                            new Series(
                                                    points.stream().map(MetricPoint::from).toList(),
                                                    item.series().totalPoints(),
                                                    item.series().sampled()));
                                })
                        .toList(),
                comparison.parameters(),
                comparison.maxPoints(),
                comparison.differencesOnly());
    }
}
