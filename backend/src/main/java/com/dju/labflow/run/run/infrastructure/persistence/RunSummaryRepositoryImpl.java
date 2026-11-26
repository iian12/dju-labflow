package com.dju.labflow.run.run.infrastructure.persistence;

import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.run.metric.domain.RunMetric;
import com.dju.labflow.run.metric.infrastructure.persistence.RunMetricEntity;
import com.dju.labflow.run.metric.infrastructure.persistence.RunMetricMapper;
import com.dju.labflow.run.run.domain.RunSummary;
import com.dju.labflow.run.run.domain.repository.RunSummaryRepository;
import com.dju.labflow.run.run.exception.RunNotFoundException;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 이력 크기와 무관하게 지표별 요약과 최신 원본만 읽는다. */
@Repository
@Transactional(readOnly = true)
public class RunSummaryRepositoryImpl implements RunSummaryRepository {
    private final EntityManager em;

    public RunSummaryRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordMetrics(List<RunMetric> records) {
        if (records.isEmpty()) return;
        // 요약이 가리키는 원본을 먼저 INSERT한다. flush는 커밋하지 않는다.
        em.flush();
        for (RunMetric record : records) {
            em.createNativeQuery(
                            """
                insert into run_metric_summary
                    (run_id, metric_key, latest_metric_id, latest_recorded_at, max_step)
                values (:run, :key, :id, cast(:recordedAt as datetime(6)), :step)
                on duplicate key update
                    latest_metric_id = case
                        when cast(:recordedAt as datetime(6)) > latest_recorded_at then :id
                        when cast(:recordedAt as datetime(6)) = latest_recorded_at
                              and :id > latest_metric_id
                        then :id else latest_metric_id end,
                    latest_recorded_at = greatest(latest_recorded_at, cast(:recordedAt as datetime(6))),
                    max_step = case
                        when max_step is null then :step
                        when :step is null then max_step
                        else greatest(max_step, :step) end
                """)
                    .setParameter("run", record.getRunId().value())
                    .setParameter("key", record.getKey())
                    .setParameter("id", record.getId().value())
                    .setParameter("recordedAt", record.getRecordedAt())
                    .setParameter("step", record.getStep())
                    .executeUpdate();
        }
    }

    @Override
    public List<RunSummary> findByProject(ProjectId project, List<Long> ids) {
        long found =
                em.createQuery(
                                "select count(r) from RunEntity r where r.projectId = :project and r.id in :ids",
                                Long.class)
                        .setParameter("project", project.value())
                        .setParameter("ids", ids)
                        .getSingleResult();
        if (found != ids.size()) throw new RunNotFoundException();
        List<?> rows =
                em.createNativeQuery(
                                """
                select m.* from run_metric_summary s
                join run_metric_entity m on m.id = s.latest_metric_id
                where s.run_id in (:ids) order by s.run_id, s.metric_key
                """,
                                RunMetricEntity.class)
                        .setParameter("ids", ids)
                        .getResultList();
        Map<Long, List<RunMetric>> values = new HashMap<>();
        for (Object row : rows) {
            RunMetricEntity metric = (RunMetricEntity) row;
            values.computeIfAbsent(metric.getRunId(), id -> new ArrayList<>())
                    .add(RunMetricMapper.toDomain(metric));
        }
        Map<Long, Long> steps = new HashMap<>();
        for (Object[] row :
                em.createQuery(
                                "select s.runId, max(s.maxStep) from RunMetricSummaryEntity s where s.runId in :ids group by s.runId",
                                Object[].class)
                        .setParameter("ids", ids)
                        .getResultList()) steps.put((Long) row[0], (Long) row[1]);
        return ids.stream()
                .map(id -> new RunSummary(id, steps.get(id), values.getOrDefault(id, List.of())))
                .toList();
    }
}
