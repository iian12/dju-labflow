package com.dju.labflow.run.parameter.infrastructure.persistence;

import com.dju.labflow.run.parameter.domain.RunParameter;
import com.dju.labflow.run.parameter.domain.repository.RunParameterRepository;
import com.dju.labflow.run.run.domain.RunId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 실행 기록 저장소 어댑터. 컬렉션 변환도 조회 트랜잭션 안에서 수행한다. */
@Repository
@Transactional(readOnly = true)
public class RunParameterRepositoryImpl implements RunParameterRepository {
    private final RunParameterJpaRepository runParameterJpaRepository;

    public RunParameterRepositoryImpl(RunParameterJpaRepository runParameterJpaRepository) {
        this.runParameterJpaRepository = runParameterJpaRepository;
    }

    @Override
    @Transactional
    public RunParameter save(RunParameter record) {
        Objects.requireNonNull(record, "Record must not be null");
        return RunParameterMapper.toDomain(
                runParameterJpaRepository.save(RunParameterMapper.toEntity(record)));
    }

    @Override
    public Optional<RunParameter> findByRunIdAndKey(RunId runId, String key) {
        Objects.requireNonNull(runId, "Run ID must not be null");
        Objects.requireNonNull(key, "Parameter key must not be null");
        return runParameterJpaRepository
                .findByRunIdAndKey(runId.value(), key)
                .map(RunParameterMapper::toDomain);
    }

    @Override
    public List<RunParameter> findByRunId(RunId runId) {
        Objects.requireNonNull(runId, "Run ID must not be null");
        return runParameterJpaRepository.findByRunIdOrderByKeyAsc(runId.value()).stream()
                .map(RunParameterMapper::toDomain)
                .toList();
    }
}
