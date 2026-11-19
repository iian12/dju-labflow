package com.dju.labflow.run.log.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 실행 기록의 JPA 조회. */
public interface RunLogJpaRepository extends JpaRepository<RunLogEntity, Long> {
    List<RunLogEntity> findByRunIdOrderByRecordedAtAscIdAsc(Long runId, Pageable pageable);

    List<RunLogEntity> findByRunIdOrderByRecordedAtDescIdDesc(Long runId, Pageable pageable);

    @Query(
            "select r from RunLogEntity r where r.runId = :runId "
                    + "and (r.recordedAt > :time or (r.recordedAt = :time and r.id > :id)) "
                    + "order by r.recordedAt asc, r.id asc")
    List<RunLogEntity> findAfter(
            @Param("runId") Long runId,
            @Param("time") Instant time,
            @Param("id") Long id,
            Pageable pageable);

    @Query(
            "select r from RunLogEntity r where r.runId = :runId "
                    + "and (r.recordedAt < :time or (r.recordedAt = :time and r.id < :id)) "
                    + "order by r.recordedAt desc, r.id desc")
    List<RunLogEntity> findBefore(
            @Param("runId") Long runId,
            @Param("time") Instant time,
            @Param("id") Long id,
            Pageable pageable);
}
