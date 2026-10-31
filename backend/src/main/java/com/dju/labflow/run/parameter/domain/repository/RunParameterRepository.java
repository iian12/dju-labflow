package com.dju.labflow.run.parameter.domain.repository;

import com.dju.labflow.run.parameter.domain.RunParameter;
import com.dju.labflow.run.run.domain.RunId;
import java.util.List;
import java.util.Optional;

/** RunParameter 신규 저장과 실행별 조회 계약. */
public interface RunParameterRepository {
    RunParameter save(RunParameter record);

    Optional<RunParameter> findByRunIdAndKey(RunId runId, String key);

    List<RunParameter> findByRunId(RunId runId);
}
