package com.dju.labflow.run.text.infrastructure.persistence;

import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.run.run.domain.RunValidation;
import com.dju.labflow.run.text.domain.RunText;
import com.dju.labflow.run.text.domain.repository.RunTextRepository;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 실행 기록 저장소 어댑터. 컬렉션 변환도 조회 트랜잭션 안에서 수행한다. */
@Repository
@Transactional(readOnly = true)
public class RunTextRepositoryImpl implements RunTextRepository {
    private final RunTextJpaRepository runTextJpaRepository;

    public RunTextRepositoryImpl(RunTextJpaRepository runTextJpaRepository) {
        this.runTextJpaRepository = runTextJpaRepository;
    }

    @Override
    @Transactional
    public RunText save(RunText record) {
        Objects.requireNonNull(record, "Record must not be null");
        return RunTextMapper.toDomain(runTextJpaRepository.save(RunTextMapper.toEntity(record)));
    }

    @Override
    public List<RunText> findByRunId(RunId runId, int limit) {
        Objects.requireNonNull(runId, "Run ID must not be null");
        return runTextJpaRepository
                .findByRunIdOrderByRecordedAtAscIdAsc(
                        runId.value(), PageRequest.of(0, RunValidation.limit(limit)))
                .stream()
                .map(RunTextMapper::toDomain)
                .toList();
    }
}
