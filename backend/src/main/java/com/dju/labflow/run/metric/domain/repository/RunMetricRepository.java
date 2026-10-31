package com.dju.labflow.run.metric.domain.repository;

import com.dju.labflow.run.metric.domain.RunMetric;
import com.dju.labflow.run.run.domain.RunId;
import java.util.List;

/** RunMetric 신규 저장과 실행별 조회 계약. */
public interface RunMetricRepository {
    RunMetric save(RunMetric record);

    /** 기록 시각과 ID 오름차순으로 최대 limit개를 조회한다(1~1000). */
    List<RunMetric> findByRunId(RunId runId, int limit);
}
