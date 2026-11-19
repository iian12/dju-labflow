package com.dju.labflow.run.systemmetric.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 실행 기록의 JPA 조회. */
public interface RunSystemMetricJpaRepository extends JpaRepository<RunSystemMetricEntity, Long> {
    List<RunSystemMetricEntity> findByRunIdOrderByRecordedAtAscIdAsc(Long runId, Pageable pageable);

    List<RunSystemMetricEntity> findByRunIdOrderByRecordedAtDescIdDesc(
            Long runId, Pageable pageable);

    @Query(
            "select r from RunSystemMetricEntity r where r.runId = :runId "
                    + "and (r.recordedAt > :time or (r.recordedAt = :time and r.id > :id)) "
                    + "order by r.recordedAt asc, r.id asc")
    List<RunSystemMetricEntity> findAfter(
            @Param("runId") Long runId,
            @Param("time") Instant time,
            @Param("id") Long id,
            Pageable pageable);

    @Query(
            "select r from RunSystemMetricEntity r where r.runId = :runId "
                    + "and (r.recordedAt < :time or (r.recordedAt = :time and r.id < :id)) "
                    + "order by r.recordedAt desc, r.id desc")
    List<RunSystemMetricEntity> findBefore(
            @Param("runId") Long runId,
            @Param("time") Instant time,
            @Param("id") Long id,
            Pageable pageable);
}
