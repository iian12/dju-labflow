package com.dju.labflow.run.metric.infrastructure.persistence;

import com.dju.labflow.run.metric.domain.RunMetric;
import com.dju.labflow.run.metric.domain.RunMetricSeries;
import com.dju.labflow.run.metric.domain.repository.RunMetricComparisonRepository;
import com.dju.labflow.run.run.domain.RunId;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 비교용 집계와 전체 Step 범위 샘플링을 DB에서 수행한다. */
@Repository
@Transactional(readOnly = true)
public class RunMetricComparisonRepositoryImpl implements RunMetricComparisonRepository {
    private final EntityManager em;

    public RunMetricComparisonRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    @Override
    public List<String> findKeys(List<RunId> runIds) {
        return em.createQuery(
                        "select distinct m.key from RunMetricEntity m where m.runId in :ids order by m.key",
                        String.class)
                .setParameter("ids", runIds.stream().map(RunId::value).toList())
                .getResultList();
    }

    @Override
    public Optional<Long> findCommonStep(List<RunId> runIds, String key) {
        return em
                .createQuery(
                        "select m.step from RunMetricEntity m where m.runId in :ids and m.key = :key "
                                + "and m.step is not null group by m.step "
                                + "having count(distinct m.runId) = :size order by m.step desc",
                        Long.class)
                .setParameter("ids", runIds.stream().map(RunId::value).toList())
                .setParameter("key", key)
                .setParameter("size", (long) runIds.size())
                .setMaxResults(1)
                .getResultList()
                .stream()
                .findFirst();
    }

    @Override
    public Optional<RunMetric> findLatest(RunId runId, String key) {
        return em
                .createQuery(
                        "select m from RunMetricEntity m where m.runId = :run and m.key = :key "
                                + "order by m.recordedAt desc, m.id desc",
                        RunMetricEntity.class)
                .setParameter("run", runId.value())
                .setParameter("key", key)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .findFirst()
                .map(RunMetricMapper::toDomain);
    }

    @Override
    public Optional<RunMetric> findAtStep(RunId runId, String key, long step) {
        return em
                .createQuery(
                        "select m from RunMetricEntity m where m.runId = :run and m.key = :key "
                                + "and m.step = :step order by m.recordedAt desc, m.id desc",
                        RunMetricEntity.class)
                .setParameter("run", runId.value())
                .setParameter("key", key)
                .setParameter("step", step)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .findFirst()
                .map(RunMetricMapper::toDomain);
    }

    @Override
    public RunMetricSeries findSeries(RunId runId, String key, int maxPoints) {
        if (maxPoints < 2 || maxPoints > 1000)
            throw new IllegalArgumentException("Max points must be between 2 and 1000");
        long total =
                em.createQuery(
                                "select count(distinct m.step) from RunMetricEntity m "
                                        + "where m.runId = :run and m.key = :key and m.step is not null",
                                Long.class)
                        .setParameter("run", runId.value())
                        .setParameter("key", key)
                        .getSingleResult();
        if (total == 0) return new RunMetricSeries(0, List.of());
        long stride = total <= maxPoints ? 1 : 1 + (total - 2) / (maxPoints - 1);
        // Both MariaDB and H2 support these window functions. Deduplication and sampling
        // happen in SQL, so a long training history is never loaded into JVM memory.
        List<?> rows =
                em.createNativeQuery(
                                """
                        with ranked as (
                            select m.*, row_number() over (
                                partition by m.step order by m.recorded_at desc, m.id desc
                            ) as step_rank
                            from run_metric_entity m
                            where m.run_id = :run and m.metric_key = :key and m.step is not null
                        ), points as (
                            select r.*, row_number() over (order by r.step) as point_number
                            from ranked r where r.step_rank = 1
                        )
                        select id, run_id, metric_key, metric_value, epoch, step, elapsed_time, recorded_at
                        from points
                        where mod(point_number - 1, :stride) = 0 or point_number = :total
                        order by step
                        """,
                                RunMetricEntity.class)
                        .setParameter("run", runId.value())
                        .setParameter("key", key)
                        .setParameter("stride", stride)
                        .setParameter("total", total)
                        .getResultList();
        List<RunMetric> points =
                rows.stream().map(row -> RunMetricMapper.toDomain((RunMetricEntity) row)).toList();
        return new RunMetricSeries(total, points);
    }
}
