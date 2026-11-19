package com.dju.labflow.run.text.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 실행 기록의 JPA 조회. */
public interface RunTextJpaRepository extends JpaRepository<RunTextEntity, Long> {
    List<RunTextEntity> findByRunIdOrderByRecordedAtAscIdAsc(Long runId, Pageable pageable);

    List<RunTextEntity> findByRunIdOrderByRecordedAtDescIdDesc(Long runId, Pageable pageable);

    @Query(
            "select r from RunTextEntity r where r.runId = :runId "
                    + "and (r.recordedAt > :time or (r.recordedAt = :time and r.id > :id)) "
                    + "order by r.recordedAt asc, r.id asc")
    List<RunTextEntity> findAfter(
            @Param("runId") Long runId,
            @Param("time") Instant time,
            @Param("id") Long id,
            Pageable pageable);

    @Query(
            "select r from RunTextEntity r where r.runId = :runId "
                    + "and (r.recordedAt < :time or (r.recordedAt = :time and r.id < :id)) "
                    + "order by r.recordedAt desc, r.id desc")
    List<RunTextEntity> findBefore(
            @Param("runId") Long runId,
            @Param("time") Instant time,
            @Param("id") Long id,
            Pageable pageable);
}
