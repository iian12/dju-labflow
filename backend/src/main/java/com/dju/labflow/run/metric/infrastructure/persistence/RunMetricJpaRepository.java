package com.dju.labflow.run.metric.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 실행 기록의 JPA 조회. */
public interface RunMetricJpaRepository extends JpaRepository<RunMetricEntity, Long> {
    List<RunMetricEntity> findByRunIdOrderByRecordedAtAscIdAsc(Long runId, Pageable pageable);

    List<RunMetricEntity> findByRunIdOrderByRecordedAtDescIdDesc(Long runId, Pageable pageable);

    @Query(
            "select r from RunMetricEntity r where r.runId = :runId "
                    + "and (r.recordedAt > :time or (r.recordedAt = :time and r.id > :id)) "
                    + "order by r.recordedAt asc, r.id asc")
    List<RunMetricEntity> findAfter(
            @Param("runId") Long runId,
            @Param("time") Instant time,
            @Param("id") Long id,
            Pageable pageable);

    @Query(
            "select r from RunMetricEntity r where r.runId = :runId "
                    + "and (r.recordedAt < :time or (r.recordedAt = :time and r.id < :id)) "
                    + "order by r.recordedAt desc, r.id desc")
    List<RunMetricEntity> findBefore(
            @Param("runId") Long runId,
            @Param("time") Instant time,
            @Param("id") Long id,
            Pageable pageable);
}
