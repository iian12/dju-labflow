package com.dju.labflow.run.run.infrastructure.persistence;

import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.run.run.domain.Run;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.run.run.domain.RunPage;
import com.dju.labflow.run.run.domain.RunStatus;
import com.dju.labflow.run.run.domain.RunValidation;
import com.dju.labflow.run.run.domain.repository.RunRepository;
import com.dju.labflow.run.run.exception.RunNotFoundException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 실행 도메인의 저장소 어댑터. 메타데이터 변환도 트랜잭션 안에서 수행한다. */
@Repository
@Transactional(readOnly = true)
public class RunRepositoryImpl implements RunRepository {
    private final RunJpaRepository runJpaRepository;

    public RunRepositoryImpl(RunJpaRepository runJpaRepository) {
        this.runJpaRepository = runJpaRepository;
    }

    @Override
    @Transactional
    public Run save(Run run) {
        Objects.requireNonNull(run, "Run must not be null");
        return RunMapper.toDomain(runJpaRepository.save(RunMapper.toEntity(run)));
    }

    @Override
    @Transactional
    public void update(Run run) {
        Objects.requireNonNull(run, "Run must not be null");
        RunEntity entity =
                runJpaRepository
                        .findById(run.getId().value())
                        .orElseThrow(RunNotFoundException::new);
        if (run.getVersion() == null || !run.getVersion().equals(entity.getVersion()))
            throw new ObjectOptimisticLockingFailureException(RunEntity.class, run.getId().value());
        entity.update(run.getStatus(), run.getEndedAt(), run.getError());
    }

    @Override
    public Optional<Run> findById(RunId id) {
        if (id == null) return Optional.empty();
        return runJpaRepository.findById(id.value()).map(RunMapper::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Run> findByIdForUpdate(RunId id) {
        Objects.requireNonNull(id, "Run ID must not be null");
        return runJpaRepository.findByIdForUpdate(id.value()).map(RunMapper::toDomain);
    }

    @Override
    public List<Run> findByProjectId(ProjectId projectId, int limit) {
        Objects.requireNonNull(projectId, "Project ID must not be null");
        return runJpaRepository
                .findByProjectIdOrderByStartedAtDescIdDesc(
                        projectId.value(), PageRequest.of(0, RunValidation.limit(limit)))
                .stream()
                .map(RunMapper::toDomain)
                .toList();
    }

    @Override
    public RunPage findPageByProjectId(ProjectId projectId, int page, int size) {
        Objects.requireNonNull(projectId, "Project ID must not be null");
        if (page < 0 || size < 1 || size > 100)
            throw new IllegalArgumentException("Invalid run page");
        var result =
                runJpaRepository.findAllByProjectIdOrderByStartedAtDescIdDesc(
                        projectId.value(), PageRequest.of(page, size));
        return new RunPage(
                result.stream().map(RunMapper::toDomain).toList(),
                page,
                size,
                result.getTotalElements(),
                runJpaRepository.countByProjectIdAndStatus(projectId.value(), RunStatus.RUNNING),
                runJpaRepository.countByProjectIdAndStatus(projectId.value(), RunStatus.COMPLETED),
                runJpaRepository.countByProjectIdAndStatus(projectId.value(), RunStatus.FAILED));
    }
}
