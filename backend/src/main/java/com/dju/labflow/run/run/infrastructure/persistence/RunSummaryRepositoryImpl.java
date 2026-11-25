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
import org.springframework.transaction.annotation.Transactional;

/** 전체 시계열을 메모리에 로드하지 않고 지표별 최신 기록을 SQL에서 선택한다. */
@Repository
@Transactional(readOnly = true)
public class RunSummaryRepositoryImpl implements RunSummaryRepository {
    private final EntityManager em;

    public RunSummaryRepositoryImpl(EntityManager em) {
        this.em = em;
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
                select id, run_id, metric_key, metric_value, epoch, step, elapsed_time, recorded_at from (
                    select m.*, row_number() over (partition by run_id, metric_key order by recorded_at desc, id desc) as rn
                    from run_metric_entity m where run_id in (:ids)
                ) ranked where rn = 1 order by run_id, metric_key
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
                                "select m.runId, max(m.step) from RunMetricEntity m where m.runId in :ids group by m.runId",
                                Object[].class)
                        .setParameter("ids", ids)
                        .getResultList()) steps.put((Long) row[0], (Long) row[1]);
        return ids.stream()
                .map(id -> new RunSummary(id, steps.get(id), values.getOrDefault(id, List.of())))
                .toList();
    }
}
