package com.dju.labflow.run.systemmetric.infrastructure.persistence;

import com.dju.labflow.run.run.domain.RecordPageQuery;
import com.dju.labflow.run.run.domain.RecordPosition;
import com.dju.labflow.run.run.domain.RecordSlice;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.run.run.domain.RunValidation;
import com.dju.labflow.run.systemmetric.domain.RunSystemMetric;
import com.dju.labflow.run.systemmetric.domain.repository.RunSystemMetricRepository;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 실행 기록 저장소 어댑터. 컬렉션 변환도 조회 트랜잭션 안에서 수행한다. */
@Repository
@Transactional(readOnly = true)
public class RunSystemMetricRepositoryImpl implements RunSystemMetricRepository {
    private final RunSystemMetricJpaRepository runSystemMetricJpaRepository;

    public RunSystemMetricRepositoryImpl(
            RunSystemMetricJpaRepository runSystemMetricJpaRepository) {
        this.runSystemMetricJpaRepository = runSystemMetricJpaRepository;
    }

    @Override
    @Transactional
    public RunSystemMetric save(RunSystemMetric record) {
        Objects.requireNonNull(record, "Record must not be null");
        return RunSystemMetricMapper.toDomain(
                runSystemMetricJpaRepository.save(RunSystemMetricMapper.toEntity(record)));
    }

    @Override
    public List<RunSystemMetric> findByRunId(RunId runId, int limit) {
        Objects.requireNonNull(runId, "Run ID must not be null");
        return runSystemMetricJpaRepository
                .findByRunIdOrderByRecordedAtAscIdAsc(
                        runId.value(), PageRequest.of(0, RunValidation.limit(limit)))
                .stream()
                .map(RunSystemMetricMapper::toDomain)
                .toList();
    }

    @Override
    public RecordSlice<RunSystemMetric> findPage(RunId runId, RecordPageQuery query) {
        Objects.requireNonNull(runId, "Run ID must not be null");
        Objects.requireNonNull(query, "Page query must not be null");
        PageRequest page = PageRequest.of(0, query.limit() + 1);
        RecordPosition position = query.position();
        List<RunSystemMetricEntity> fetched;
        if (position == null) {
            fetched =
                    query.backwards()
                            ? runSystemMetricJpaRepository.findByRunIdOrderByRecordedAtDescIdDesc(
                                    runId.value(), page)
                            : runSystemMetricJpaRepository.findByRunIdOrderByRecordedAtAscIdAsc(
                                    runId.value(), page);
        } else {
            fetched =
                    query.backwards()
                            ? runSystemMetricJpaRepository.findBefore(
                                    runId.value(), position.recordedAt(), position.id(), page)
                            : runSystemMetricJpaRepository.findAfter(
                                    runId.value(), position.recordedAt(), position.id(), page);
        }
        return RecordSlice.from(
                fetched.stream().map(RunSystemMetricMapper::toDomain).toList(), query);
    }
}
