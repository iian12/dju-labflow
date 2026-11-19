package com.dju.labflow.run.run.application;

import com.dju.labflow.global.validation.InputValidation;
import com.dju.labflow.project.project.application.ProjectService;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.run.metric.domain.RunMetric;
import com.dju.labflow.run.metric.domain.RunMetricSeries;
import com.dju.labflow.run.metric.domain.repository.RunMetricComparisonRepository;
import com.dju.labflow.run.run.domain.*;
import com.dju.labflow.run.run.domain.repository.RunSummaryRepository;
import com.dju.labflow.user.domain.UserId;
import java.util.*;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 프로젝트 공개 정책을 적용해 목록의 일괄 요약과 단일 실행의 곡선을 조회한다. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RunSummaryService {
    private final ProjectService projects;
    private final RunSummaryRepository summaries;
    private final RunQueryService queries;
    private final RunMetricComparisonRepository metrics;

    public RunSummaryService(
            ProjectService projects,
            RunSummaryRepository summaries,
            RunQueryService queries,
            RunMetricComparisonRepository metrics) {
        this.projects = projects;
        this.summaries = summaries;
        this.queries = queries;
        this.metrics = metrics;
    }

    public List<RunSummary> summaries(@Nullable UserId user, ProjectId project, List<Long> ids) {
        if (ids == null
                || ids.isEmpty()
                || ids.size() > 100
                || new HashSet<>(ids).size() != ids.size()
                || ids.stream().anyMatch(id -> id == null || id <= 0))
            throw new IllegalArgumentException("Invalid Run selection");
        projects.get(user, project);
        return summaries.findByProject(project, ids);
    }

    public record MetricCurve(String metricKey, RunMetric latestMetric, RunMetricSeries series) {}

    public MetricCurve curve(
            @Nullable UserId user, ProjectId project, RunId run, String key, int limit) {
        InputValidation.text(key, "Metric key", 100);
        if (limit < 2 || limit > 1000) throw new IllegalArgumentException("Invalid curve limit");
        queries.get(user, project, run);
        return new MetricCurve(
                key,
                metrics.findLatest(run, key).orElse(null),
                metrics.findSeries(run, key, limit));
    }
}
