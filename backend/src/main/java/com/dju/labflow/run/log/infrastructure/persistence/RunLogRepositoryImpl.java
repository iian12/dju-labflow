package com.dju.labflow.run.log.infrastructure.persistence;

import com.dju.labflow.run.log.domain.RunLog;
import com.dju.labflow.run.log.domain.repository.RunLogRepository;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.run.run.domain.RunValidation;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 실행 기록 저장소 어댑터. 컬렉션 변환도 조회 트랜잭션 안에서 수행한다. */
@Repository
@Transactional(readOnly = true)
public class RunLogRepositoryImpl implements RunLogRepository {
    private final RunLogJpaRepository runLogJpaRepository;

    public RunLogRepositoryImpl(RunLogJpaRepository runLogJpaRepository) {
        this.runLogJpaRepository = runLogJpaRepository;
    }

    @Override
    @Transactional
    public RunLog save(RunLog record) {
        Objects.requireNonNull(record, "Record must not be null");
        return RunLogMapper.toDomain(runLogJpaRepository.save(RunLogMapper.toEntity(record)));
    }

    @Override
    public List<RunLog> findByRunId(RunId runId, int limit) {
        Objects.requireNonNull(runId, "Run ID must not be null");
        return runLogJpaRepository
                .findByRunIdOrderByRecordedAtAscIdAsc(
                        runId.value(), PageRequest.of(0, RunValidation.limit(limit)))
                .stream()
                .map(RunLogMapper::toDomain)
                .toList();
    }
}
