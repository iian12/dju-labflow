package com.dju.labflow.run.artifact.domain.repository;

import com.dju.labflow.run.artifact.domain.*;
import com.dju.labflow.run.run.domain.RunId;
import java.util.List;
import java.util.Optional;

/** 파일 메타데이터의 신규 저장, 버전 기반 상태 갱신과 실행별 조회 계약. */
public interface RunArtifactRepository {
    RunArtifact save(RunArtifact artifact);

    void update(RunArtifact artifact);

    Optional<RunArtifact> findById(RunArtifactId id);

    Optional<RunArtifact> findByRunIdAndClientRequestId(RunId runId, String clientRequestId);

    /** 생성 시각과 ID 오름차순으로 최대 limit개를 조회한다(1~1000). */
    List<RunArtifact> findByRunId(RunId runId, int limit);

    /** 일반 목록에는 업로드 검증이 끝난 파일만 노출한다. */
    List<RunArtifact> findReadyByRunId(RunId runId, int limit);
}
