package com.dju.labflow.run.run.infrastructure.persistence;

import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.run.run.domain.Run;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.run.run.domain.RunPage;
import com.dju.labflow.run.run.domain.RunSearch;
import com.dju.labflow.run.run.domain.RunStatus;
import com.dju.labflow.run.run.domain.RunValidation;
import com.dju.labflow.run.run.domain.repository.RunRepository;
import com.dju.labflow.run.run.exception.RunNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 실행 도메인의 저장소 어댑터. 메타데이터 변환도 트랜잭션 안에서 수행한다. */
@Repository
@Transactional(readOnly = true)
public class RunRepositoryImpl implements RunRepository {
    private final RunJpaRepository runJpaRepository;
    private final EntityManager em;
    private final Clock clock;

    public RunRepositoryImpl(RunJpaRepository runJpaRepository, EntityManager em, Clock clock) {
        this.runJpaRepository = runJpaRepository;
        this.em = em;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Run save(Run run) {
        Objects.requireNonNull(run, "Run must not be null");
        return RunMapper.toDomain(runJpaRepository.save(RunMapper.toEntity(run)));
    }

    @Override
    @Transactional
    public void update(Run run) {
        Objects.requireNonNull(run, "Run must not be null");
        RunEntity entity =
                runJpaRepository
                        .findById(run.getId().value())
                        .orElseThrow(RunNotFoundException::new);
        if (run.getVersion() == null || !run.getVersion().equals(entity.getVersion()))
            throw new ObjectOptimisticLockingFailureException(RunEntity.class, run.getId().value());
        entity.update(run.getStatus(), run.getEndedAt(), run.getError());
        entity.updateHeartbeat(run.getLastHeartbeatAt());
    }

    @Override
    public Optional<Run> findById(RunId id) {
        if (id == null) return Optional.empty();
        return runJpaRepository.findById(id.value()).map(RunMapper::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Run> findByIdForUpdate(RunId id) {
        Objects.requireNonNull(id, "Run ID must not be null");
        return runJpaRepository.findByIdForUpdate(id.value()).map(RunMapper::toDomain);
    }

    @Override
    public List<Run> findByProjectId(ProjectId projectId, int limit) {
        Objects.requireNonNull(projectId, "Project ID must not be null");
        return runJpaRepository
                .findByProjectIdOrderByStartedAtDescIdDesc(
                        projectId.value(), PageRequest.of(0, RunValidation.limit(limit)))
                .stream()
                .map(RunMapper::toDomain)
                .toList();
    }

    @Override
    public RunPage findPageByProjectId(ProjectId projectId, int page, int size, RunSearch search) {
        Objects.requireNonNull(projectId, "Project ID must not be null");
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid run page");
        String where = " where r.projectId = :project";
        if (!search.name().isEmpty()) where += " and lower(r.name) like :name escape '!'";
        if (!search.statuses().isEmpty()) where += " and r.status in :statuses";
        if (!search.runIds().isEmpty()) where += " and r.id in :ids";
        String join = "", order = "r.startedAt desc, r.id desc";
        if (search.sort().equals("high") || search.sort().equals("low")) {
            join =
                    " left join RunMetricEntity m on m.runId = r.id and m.key = :metricKey "
                            + "and not exists (select n.id from RunMetricEntity n where n.runId = m.runId "
                            + "and n.key = m.key and (n.recordedAt > m.recordedAt "
                            + "or (n.recordedAt = m.recordedAt and n.id > m.id)))";
            order =
                    "case when m.value is null then 1 else 0 end, m.value "
                            + (search.sort().equals("high") ? "desc" : "asc")
                            + ", "
                            + order;
        } else if (search.sort().equals("duration")) {
            order = "timestampdiff(nanosecond, r.startedAt, coalesce(r.endedAt, :now)), " + order;
        }
        TypedQuery<RunEntity> items =
                em.createQuery(
                        "select r from RunEntity r" + join + where + " order by " + order,
                        RunEntity.class);
        TypedQuery<Long> count =
                em.createQuery("select count(r) from RunEntity r" + where, Long.class);
        bind(items, projectId, search);
        bind(count, projectId, search);
        if (!join.isEmpty()) items.setParameter("metricKey", search.metricKey());
        if (search.sort().equals("duration")) items.setParameter("now", clock.instant());
        Map<RunStatus, Long> counts = new EnumMap<>(RunStatus.class);
        for (Object[] row :
                em.createQuery(
                                "select r.status, count(r) from RunEntity r "
                                        + "where r.projectId = :project group by r.status",
                                Object[].class)
                        .setParameter("project", projectId.value())
                        .getResultList()) counts.put((RunStatus) row[0], (Long) row[1]);
        List<String> keys =
                em.createQuery(
                                "select distinct m.key from RunMetricEntity m "
                                        + "where m.runId in (select r.id from RunEntity r where r.projectId = :project) order by m.key",
                                String.class)
                        .setParameter("project", projectId.value())
                        .getResultList();
        return new RunPage(
                items
                        .setFirstResult(Math.toIntExact((long) page * size))
                        .setMaxResults(size)
                        .getResultList()
                        .stream()
                        .map(RunMapper::toDomain)
                        .toList(),
                page,
                size,
                count.getSingleResult(),
                counts.getOrDefault(RunStatus.RUNNING, 0L),
                counts.getOrDefault(RunStatus.COMPLETED, 0L),
                counts.getOrDefault(RunStatus.FAILED, 0L),
                keys);
    }

    private void bind(TypedQuery<?> query, ProjectId project, RunSearch search) {
        query.setParameter("project", project.value());
        if (!search.name().isEmpty())
            query.setParameter(
                    "name",
                    "%"
                            + search.name()
                                    .toLowerCase(java.util.Locale.ROOT)
                                    .replace("!", "!!")
                                    .replace("%", "!%")
                                    .replace("_", "!_")
                            + "%");
        if (!search.statuses().isEmpty()) query.setParameter("statuses", search.statuses());
        if (!search.runIds().isEmpty()) query.setParameter("ids", search.runIds());
    }
}
