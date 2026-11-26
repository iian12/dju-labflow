package com.dju.labflow.run.run.domain.repository;

import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.run.metric.domain.RunMetric;
import com.dju.labflow.run.run.domain.RunSummary;
import java.util.List;

/** 선택한 실행들의 요약을 일괄 조회한다. 모든 실행이 프로젝트에 속해야 한다. */
public interface RunSummaryRepository {
    /** 호출자의 메트릭 저장 트랜잭션에 참여한다. 프로젝트 → Run 잠금을 먼저 획득해야 한다. */
    void recordMetrics(List<RunMetric> records);

    List<RunSummary> findByProject(ProjectId project, List<Long> ids);
}
