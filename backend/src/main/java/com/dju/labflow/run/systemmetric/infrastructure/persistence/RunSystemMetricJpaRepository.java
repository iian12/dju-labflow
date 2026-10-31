package com.dju.labflow.run.systemmetric.infrastructure.persistence;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** 실행 기록의 JPA 조회. */
public interface RunSystemMetricJpaRepository extends JpaRepository<RunSystemMetricEntity, Long> {
    List<RunSystemMetricEntity> findByRunIdOrderByRecordedAtAscIdAsc(Long runId, Pageable pageable);
}
