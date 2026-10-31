package com.dju.labflow.run.parameter.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 실행 기록의 JPA 조회. */
public interface RunParameterJpaRepository extends JpaRepository<RunParameterEntity, Long> {
    Optional<RunParameterEntity> findByRunIdAndKey(Long runId, String key);

    List<RunParameterEntity> findByRunIdOrderByKeyAsc(Long runId);
}
