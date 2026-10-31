package com.dju.labflow.run.text.domain.repository;

import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.run.text.domain.RunText;
import java.util.List;

/** RunText 신규 저장과 실행별 조회 계약. */
public interface RunTextRepository {
    RunText save(RunText record);

    /** 기록 시각과 ID 오름차순으로 최대 limit개를 조회한다(1~1000). */
    List<RunText> findByRunId(RunId runId, int limit);
}
