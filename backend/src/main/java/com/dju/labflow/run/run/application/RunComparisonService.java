package com.dju.labflow.run.run.application;

import com.dju.labflow.project.project.application.ProjectService;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.run.metric.domain.RunMetric;
import com.dju.labflow.run.metric.domain.repository.RunMetricComparisonRepository;
import com.dju.labflow.run.parameter.domain.repository.RunParameterRepository;
import com.dju.labflow.run.run.application.RunComparison.*;
import com.dju.labflow.run.run.domain.Run;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.run.run.domain.repository.RunRepository;
import com.dju.labflow.run.run.exception.RunNotFoundException;
import com.dju.labflow.user.domain.UserId;
import java.math.BigDecimal;
import java.util.*;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 프로젝트 접근과 Run 소속 검증 후 하나의 읽기 스냅샷에서 비교를 구성한다. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RunComparisonService {
    private final ProjectService projects;
    private final RunRepository runs;
    private final RunParameterRepository parameters;
    private final RunMetricComparisonRepository metrics;

    public RunComparisonService(
            ProjectService projects,
            RunRepository runs,
            RunParameterRepository parameters,
            RunMetricComparisonRepository metrics) {
        this.projects = projects;
        this.runs = runs;
        this.parameters = parameters;
        this.metrics = metrics;
    }

    public RunComparison compare(
            @Nullable UserId user, ProjectId projectId, RunComparisonQuery query) {
        projects.get(user, projectId);
        // Validate every selected run before querying any metric or parameter data.
        List<Run> selected =
                query.runIds().stream()
                        .map(
                                id ->
                                        runs.findById(id)
                                                .filter(run -> run.getProjectId().equals(projectId))
                                                .orElseThrow(RunNotFoundException::new))
                        .toList();
        Long commonStep = metrics.findCommonStep(query.runIds(), query.metricKey()).orElse(null);
        Map<RunId, RunMetric> common = new HashMap<>();
        if (commonStep != null) {
            for (RunId id : query.runIds())
                metrics.findAtStep(id, query.metricKey(), commonStep)
                        .ifPresent(point -> common.put(id, point));
        }
        RunMetric baseline = common.get(query.baselineRunId());
        List<ComparedRun> compared =
                selected.stream()
                        .map(
                                run -> {
                                    RunMetric point = common.get(run.getId());
                                    BigDecimal delta =
                                            baseline == null || point == null
                                                    ? null
                                                    : BigDecimal.valueOf(point.getValue())
                                                            .subtract(
                                                                    BigDecimal.valueOf(
                                                                            baseline.getValue()));
                                    return new ComparedRun(
                                            run,
                                            metrics.findLatest(run.getId(), query.metricKey())
                                                    .orElse(null),
                                            point,
                                            delta,
                                            metrics.findSeries(
                                                    run.getId(),
                                                    query.metricKey(),
                                                    query.maxPoints()));
                                })
                        .toList();

        List<Map<String, String>> settings =
                selected.stream()
                        .map(
                                run -> {
                                    Map<String, String> values = new HashMap<>();
                                    parameters
                                            .findByRunId(run.getId())
                                            .forEach(p -> values.put(p.getKey(), p.getValue()));
                                    return values;
                                })
                        .toList();
        Set<String> keys = new TreeSet<>();
        settings.forEach(values -> keys.addAll(values.keySet()));
        List<ParameterComparison> rows =
                keys.stream()
                        .map(
                                key -> {
                                    List<String> values =
                                            settings.stream().map(map -> map.get(key)).toList();
                                    return new ParameterComparison(
                                            key, values, new HashSet<>(values).size() > 1);
                                })
                        .filter(row -> !query.differencesOnly() || row.different())
                        .toList();
        return new RunComparison(
                query.metricKey(),
                query.baselineRunId(),
                commonStep,
                metrics.findKeys(query.runIds()),
                compared,
                rows,
                query.maxPoints(),
                query.differencesOnly());
    }
}
