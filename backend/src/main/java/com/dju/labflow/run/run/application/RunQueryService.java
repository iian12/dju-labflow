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
import java.util.List;
import java.util.function.Function;
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
    private final RecordCursorCodec cursors;

    public RunQueryService(
            ProjectService projects,
            RunRepository runs,
            RunParameterRepository parameters,
            RunMetricRepository metrics,
            RunLogRepository logs,
            RunTextRepository texts,
            RunSystemMetricRepository systems,
            RequestValidator validator,
            RecordCursorCodec cursors) {
        this.projects = projects;
        this.runs = runs;
        this.parameters = parameters;
        this.metrics = metrics;
        this.logs = logs;
        this.texts = texts;
        this.systems = systems;
        this.validator = validator;
        this.cursors = cursors;
    }

    public RunPage list(@Nullable UserId user, ProjectId projectId, RunListQuery query) {
        validator.validate(query);
        projects.get(user, projectId);
        return runs.findPageByProjectId(projectId, query.page(), query.size(), query.search());
    }

    public Run get(@Nullable UserId user, ProjectId projectId, RunId runId) {
        projects.get(user, projectId);
        return runs.findById(runId)
                .filter(run -> run.getProjectId().equals(projectId))
                .orElseThrow(RunNotFoundException::new);
    }

    public RunRecords records(@Nullable UserId user, ProjectId projectId, RunId runId, int limit) {
        return records(user, projectId, runId, limit, false);
    }

    public RunRecords records(
            @Nullable UserId user, ProjectId projectId, RunId runId, int limit, boolean latest) {
        RunValidation.limit(limit);
        get(user, projectId, runId);
        RecordPageQuery query = new RecordPageQuery(limit, null, true);
        return new RunRecords(
                parameters.findByRunId(runId),
                latest ? metrics.findPage(runId, query).items() : metrics.findByRunId(runId, limit),
                latest ? logs.findPage(runId, query).items() : logs.findByRunId(runId, limit),
                latest ? texts.findPage(runId, query).items() : texts.findByRunId(runId, limit),
                latest ? systems.findPage(runId, query).items() : systems.findByRunId(runId, limit),
                limit);
    }

    public RunRecordPage<?> recordPage(
            @Nullable UserId user,
            ProjectId projectId,
            RunId runId,
            RecordKind kind,
            int limit,
            String before,
            String after,
            boolean latest) {
        get(user, projectId, runId);
        if ((before != null && after != null) || (latest && (before != null || after != null)))
            throw new IllegalArgumentException("Use one of latest, before, or after");
        String cursor = before != null ? before : after;
        RecordPosition position = cursor == null ? null : cursors.decode(cursor, runId, kind);
        RecordPageQuery query = new RecordPageQuery(limit, position, latest || before != null);
        return switch (kind) {
            case METRICS ->
                    page(
                            runId,
                            kind,
                            metrics.findPage(runId, query),
                            query,
                            m -> new RecordPosition(m.getRecordedAt(), m.getId().value()));
            case LOGS ->
                    page(
                            runId,
                            kind,
                            logs.findPage(runId, query),
                            query,
                            l -> new RecordPosition(l.getRecordedAt(), l.getId().value()));
            case TEXTS ->
                    page(
                            runId,
                            kind,
                            texts.findPage(runId, query),
                            query,
                            t -> new RecordPosition(t.getRecordedAt(), t.getId().value()));
            case SYSTEM_METRICS ->
                    page(
                            runId,
                            kind,
                            systems.findPage(runId, query),
                            query,
                            s -> new RecordPosition(s.getRecordedAt(), s.getId().value()));
        };
    }

    private <T> RunRecordPage<T> page(
            RunId runId,
            RecordKind kind,
            RecordSlice<T> slice,
            RecordPageQuery query,
            Function<T, RecordPosition> position) {
        List<T> items = slice.items();
        String before =
                items.isEmpty()
                        ? null
                        : cursors.encode(runId, kind, position.apply(items.getFirst()));
        String after =
                items.isEmpty()
                        ? null
                        : cursors.encode(runId, kind, position.apply(items.getLast()));
        return new RunRecordPage<>(
                items,
                query.limit(),
                slice.hasMore(),
                before,
                after,
                slice.hasMore() ? (query.backwards() ? before : after) : null);
    }
}
