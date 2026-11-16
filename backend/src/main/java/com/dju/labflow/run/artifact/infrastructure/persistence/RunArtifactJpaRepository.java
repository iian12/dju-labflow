package com.dju.labflow.run.artifact.infrastructure.persistence;

import com.dju.labflow.run.artifact.domain.ArtifactStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** 실행별 파일 목록과 SDK 재전송 식별자를 조회하는 JPA 저장소. */
public interface RunArtifactJpaRepository extends JpaRepository<RunArtifactEntity, Long> {
    Optional<RunArtifactEntity> findByRunIdAndClientRequestId(Long runId, String clientRequestId);

    List<RunArtifactEntity> findByRunIdOrderByCreatedAtAscIdAsc(Long runId, Pageable pageable);

    List<RunArtifactEntity> findByRunIdAndStatusOrderByCreatedAtAscIdAsc(
            Long runId, ArtifactStatus status, Pageable pageable);
}
