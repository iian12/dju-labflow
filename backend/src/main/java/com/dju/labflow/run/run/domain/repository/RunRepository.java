package com.dju.labflow.run.run.domain.repository;

import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.run.run.domain.Run;
import com.dju.labflow.run.run.domain.RunId;
import java.util.List;
import java.util.Optional;

/** 실행의 신규 저장, 상태 변경, 프로젝트별 조회 계약. */
public interface RunRepository {
    Run save(Run run);

    /** 조회한 버전이 일치할 때만 상태를 변경한다. 갱신 후에는 다시 조회한다. */
    void update(Run run);

    Optional<Run> findById(RunId id);

    /** 서비스 쓰기 트랜잭션 안에서 호출하며 기록 추가와 종료 작업을 직렬화한다. */
    Optional<Run> findByIdForUpdate(RunId id);

    List<Run> findByProjectId(ProjectId projectId, int limit);
}
