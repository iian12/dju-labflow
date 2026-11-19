package com.dju.labflow.run.run.application;

import com.dju.labflow.run.metric.domain.RunMetric;
import com.dju.labflow.run.metric.domain.RunMetricSeries;
import com.dju.labflow.run.run.domain.Run;
import com.dju.labflow.run.run.domain.RunId;
import java.math.BigDecimal;
import java.util.List;

/** 차트와 독립적인 비교 요약 및 선택 순서에 맞춘 파라미터 행. */
public record RunComparison(
        String metricKey,
        RunId baselineRunId,
        Long commonStep,
        List<String> availableMetrics,
        List<ComparedRun> runs,
        List<ParameterComparison> parameters,
        int maxPoints,
        boolean differencesOnly) {
    public record ComparedRun(
            Run run,
            RunMetric latestMetric,
            RunMetric commonStepMetric,
            BigDecimal deltaFromBaseline,
            RunMetricSeries series) {}

    /** values는 응답 runs와 같은 순서이며 미설정 파라미터는 null이다. */
    public record ParameterComparison(String key, List<String> values, boolean different) {}
}
