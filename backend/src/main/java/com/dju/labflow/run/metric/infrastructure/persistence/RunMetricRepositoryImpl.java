package com.dju.labflow.run.metric.infrastructure.persistence;

import com.dju.labflow.run.metric.domain.RunMetric;
import com.dju.labflow.run.metric.domain.repository.RunMetricRepository;
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
public class RunMetricRepositoryImpl implements RunMetricRepository {
    private final RunMetricJpaRepository runMetricJpaRepository;

    public RunMetricRepositoryImpl(RunMetricJpaRepository runMetricJpaRepository) {
        this.runMetricJpaRepository = runMetricJpaRepository;
    }

    @Override
    @Transactional
    public RunMetric save(RunMetric record) {
        Objects.requireNonNull(record, "Record must not be null");
        return RunMetricMapper.toDomain(
                runMetricJpaRepository.save(RunMetricMapper.toEntity(record)));
    }

    @Override
    public List<RunMetric> findByRunId(RunId runId, int limit) {
        Objects.requireNonNull(runId, "Run ID must not be null");
        return runMetricJpaRepository
                .findByRunIdOrderByRecordedAtAscIdAsc(
                        runId.value(), PageRequest.of(0, RunValidation.limit(limit)))
                .stream()
                .map(RunMetricMapper::toDomain)
                .toList();
    }
}
