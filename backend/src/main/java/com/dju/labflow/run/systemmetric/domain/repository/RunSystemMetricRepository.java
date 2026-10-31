package com.dju.labflow.run.systemmetric.domain.repository;

import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.run.systemmetric.domain.RunSystemMetric;
import java.util.List;

/** RunSystemMetric 신규 저장과 실행별 조회 계약. */
public interface RunSystemMetricRepository {
    RunSystemMetric save(RunSystemMetric record);

    /** 기록 시각과 ID 오름차순으로 최대 limit개를 조회한다(1~1000). */
    List<RunSystemMetric> findByRunId(RunId runId, int limit);
}
