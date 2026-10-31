package com.dju.labflow.run.log.infrastructure.persistence;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** 실행 기록의 JPA 조회. */
public interface RunLogJpaRepository extends JpaRepository<RunLogEntity, Long> {
    List<RunLogEntity> findByRunIdOrderByRecordedAtAscIdAsc(Long runId, Pageable pageable);
}
