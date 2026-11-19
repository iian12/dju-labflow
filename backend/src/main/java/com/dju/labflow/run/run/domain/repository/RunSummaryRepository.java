package com.dju.labflow.run.run.domain.repository;

import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.run.run.domain.RunSummary;
import java.util.List;

/** 선택한 실행들의 요약을 일괄 조회한다. 모든 실행이 프로젝트에 속해야 한다. */
public interface RunSummaryRepository {
    List<RunSummary> findByProject(ProjectId project, List<Long> ids);
}
