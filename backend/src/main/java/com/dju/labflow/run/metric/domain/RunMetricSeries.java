package com.dju.labflow.run.metric.domain;

import java.util.List;

/** 전체 Step 범위에서 선택한 실제 기록. 첫 점과 마지막 점은 항상 포함한다. */
public record RunMetricSeries(long totalPoints, List<RunMetric> points) {
    public RunMetricSeries {
        points = List.copyOf(points);
    }

    public boolean sampled() {
        return totalPoints > points.size();
    }
}
