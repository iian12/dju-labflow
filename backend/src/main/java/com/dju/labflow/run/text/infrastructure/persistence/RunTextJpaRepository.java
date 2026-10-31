package com.dju.labflow.run.text.infrastructure.persistence;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** 실행 기록의 JPA 조회. */
public interface RunTextJpaRepository extends JpaRepository<RunTextEntity, Long> {
    List<RunTextEntity> findByRunIdOrderByRecordedAtAscIdAsc(Long runId, Pageable pageable);
}
