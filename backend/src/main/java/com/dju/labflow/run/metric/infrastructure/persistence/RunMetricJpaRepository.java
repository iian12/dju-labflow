package com.dju.labflow.run.metric.infrastructure.persistence;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** 실행 기록의 JPA 조회. */
public interface RunMetricJpaRepository extends JpaRepository<RunMetricEntity, Long> {
    List<RunMetricEntity> findByRunIdOrderByRecordedAtAscIdAsc(Long runId, Pageable pageable);
}
