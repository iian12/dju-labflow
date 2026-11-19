package com.dju.labflow.run.metric.domain.repository;

import com.dju.labflow.run.metric.domain.RunMetric;
import com.dju.labflow.run.metric.domain.RunMetricSeries;
import com.dju.labflow.run.run.domain.RunId;
import java.util.List;
import java.util.Optional;

/** 비교용 지표 조회. 요약은 차트 샘플링과 무관하게 원본 전체에서 계산한다. */
public interface RunMetricComparisonRepository {
    List<String> findKeys(List<RunId> runIds);

    Optional<Long> findCommonStep(List<RunId> runIds, String key);

    Optional<RunMetric> findLatest(RunId runId, String key);

    Optional<RunMetric> findAtStep(RunId runId, String key, long step);

    RunMetricSeries findSeries(RunId runId, String key, int maxPoints);
}
