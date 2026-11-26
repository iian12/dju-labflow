package com.dju.labflow.run.artifact.infrastructure.persistence;

import com.dju.labflow.run.artifact.domain.*;
import com.dju.labflow.run.artifact.domain.repository.RunArtifactRepository;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.run.run.domain.RunValidation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 신규 저장과 버전을 검사하는 상태 갱신을 분리한 파일 메타데이터 저장소. */
@Repository
@Transactional(readOnly = true)
public class RunArtifactRepositoryImpl implements RunArtifactRepository {
    private final RunArtifactJpaRepository artifacts;

    public RunArtifactRepositoryImpl(RunArtifactJpaRepository artifacts) {
        this.artifacts = artifacts;
    }

    @Override
    @Transactional
    public RunArtifact save(RunArtifact artifact) {
        Objects.requireNonNull(artifact, "RunArtifact must not be null");
        return RunArtifactMapper.toDomain(artifacts.save(RunArtifactMapper.toEntity(artifact)));
    }

    @Override
    @Transactional
    public void update(RunArtifact artifact) {
        Objects.requireNonNull(artifact, "RunArtifact must not be null");
        RunArtifactEntity entity =
                artifacts
                        .findById(artifact.getId().value())
                        .orElseThrow(() -> new IllegalArgumentException("RunArtifact not found"));
        if (artifact.getVersion() == null || !artifact.getVersion().equals(entity.getVersion()))
            throw new ObjectOptimisticLockingFailureException(
                    RunArtifactEntity.class, artifact.getId().value());
        entity.update(
                artifact.getStatus(),
                artifact.getSizeBytes(),
                artifact.getSha256(),
                artifact.getUploadedAt());
    }

    @Override
    public Optional<RunArtifact> findById(RunArtifactId id) {
        if (id == null) return Optional.empty();
        return artifacts.findById(id.value()).map(RunArtifactMapper::toDomain);
    }

    @Override
    public List<RunArtifact> findByRunId(RunId runId, int limit) {
        Objects.requireNonNull(runId, "Run ID must not be null");
        return artifacts
                .findByRunIdOrderByCreatedAtAscIdAsc(
                        runId.value(), PageRequest.of(0, RunValidation.limit(limit)))
                .stream()
                .map(RunArtifactMapper::toDomain)
                .toList();
    }

    @Override
    public List<RunArtifact> findReadyByRunId(RunId runId, int limit) {
        Objects.requireNonNull(runId, "Run ID must not be null");
        return artifacts
                .findByRunIdAndStatusOrderByCreatedAtAscIdAsc(
                        runId.value(),
                        ArtifactStatus.READY,
                        PageRequest.of(0, RunValidation.limit(limit)))
                .stream()
                .map(RunArtifactMapper::toDomain)
                .toList();
    }
}
