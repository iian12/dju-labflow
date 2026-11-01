package com.dju.labflow.run.run.application;

import com.dju.labflow.global.validation.RequestValidator;
import com.dju.labflow.project.project.application.ProjectService;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.run.log.domain.repository.RunLogRepository;
import com.dju.labflow.run.metric.domain.repository.RunMetricRepository;
import com.dju.labflow.run.parameter.domain.repository.RunParameterRepository;
import com.dju.labflow.run.run.domain.*;
import com.dju.labflow.run.run.domain.repository.RunRepository;
import com.dju.labflow.run.run.exception.RunNotFoundException;
import com.dju.labflow.run.systemmetric.domain.repository.RunSystemMetricRepository;
import com.dju.labflow.run.text.domain.repository.RunTextRepository;
import com.dju.labflow.user.domain.UserId;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 웹 사용자는 프로젝트 공개 정책으로 조회한다. SDK Bearer 인증 정책과 분리한다. */
@Service
@Transactional(readOnly = true)
public class RunQueryService {
    private final ProjectService projects;
    private final RunRepository runs;
    private final RunParameterRepository parameters;
    private final RunMetricRepository metrics;
    private final RunLogRepository logs;
    private final RunTextRepository texts;
    private final RunSystemMetricRepository systems;
    private final RequestValidator validator;

    public RunQueryService(
            ProjectService projects,
            RunRepository runs,
            RunParameterRepository parameters,
            RunMetricRepository metrics,
            RunLogRepository logs,
            RunTextRepository texts,
            RunSystemMetricRepository systems,
            RequestValidator validator) {
        this.projects = projects;
        this.runs = runs;
        this.parameters = parameters;
        this.metrics = metrics;
        this.logs = logs;
        this.texts = texts;
        this.systems = systems;
        this.validator = validator;
    }

    public RunPage list(@Nullable UserId user, ProjectId projectId, RunListQuery query) {
        validator.validate(query);
        projects.get(user, projectId);
        return runs.findPageByProjectId(projectId, query.page(), query.size());
    }

    public Run get(@Nullable UserId user, ProjectId projectId, RunId runId) {
        projects.get(user, projectId);
        return runs.findById(runId)
                .filter(run -> run.getProjectId().equals(projectId))
                .orElseThrow(RunNotFoundException::new);
    }

    public RunRecords records(@Nullable UserId user, ProjectId projectId, RunId runId, int limit) {
        RunValidation.limit(limit);
        get(user, projectId, runId);
        return new RunRecords(
                parameters.findByRunId(runId),
                metrics.findByRunId(runId, limit),
                logs.findByRunId(runId, limit),
                texts.findByRunId(runId, limit),
                systems.findByRunId(runId, limit),
                limit);
    }
}
