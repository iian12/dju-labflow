package com.dju.labflow.run.log.domain.repository;

import com.dju.labflow.run.log.domain.RunLog;
import com.dju.labflow.run.run.domain.RunId;
import java.util.List;

/** RunLog 신규 저장과 실행별 조회 계약. */
public interface RunLogRepository {
    RunLog save(RunLog record);

    /** 기록 시각과 ID 오름차순으로 최대 limit개를 조회한다(1~1000). */
    List<RunLog> findByRunId(RunId runId, int limit);
}
