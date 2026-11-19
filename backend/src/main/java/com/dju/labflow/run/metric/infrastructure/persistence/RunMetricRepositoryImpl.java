package com.dju.labflow.run.metric.infrastructure.persistence;

import com.dju.labflow.run.metric.domain.RunMetric;
import com.dju.labflow.run.metric.domain.repository.RunMetricRepository;
import com.dju.labflow.run.run.domain.RecordPageQuery;
import com.dju.labflow.run.run.domain.RecordSlice;
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

    @Override
    public RecordSlice<RunMetric> findPage(RunId runId, RecordPageQuery query) {
        Objects.requireNonNull(runId, "Run ID must not be null");
        Objects.requireNonNull(query, "Page query must not be null");
        var page = PageRequest.of(0, query.limit() + 1);
        var position = query.position();
        List<RunMetricEntity> fetched;
        if (position == null) {
            fetched =
                    query.backwards()
                            ? runMetricJpaRepository.findByRunIdOrderByRecordedAtDescIdDesc(
                                    runId.value(), page)
                            : runMetricJpaRepository.findByRunIdOrderByRecordedAtAscIdAsc(
                                    runId.value(), page);
        } else {
            fetched =
                    query.backwards()
                            ? runMetricJpaRepository.findBefore(
                                    runId.value(), position.recordedAt(), position.id(), page)
                            : runMetricJpaRepository.findAfter(
                                    runId.value(), position.recordedAt(), position.id(), page);
        }
        return RecordSlice.from(fetched.stream().map(RunMetricMapper::toDomain).toList(), query);
    }
}
