package com.dju.labflow.run.run.application;

import com.dju.labflow.global.id.IdGenerator;
import com.dju.labflow.global.validation.RequestValidator;
import com.dju.labflow.project.key.domain.ProjectApiKeyPrincipal;
import com.dju.labflow.run.log.application.RunLogCommand;
import com.dju.labflow.run.log.domain.*;
import com.dju.labflow.run.log.domain.repository.RunLogRepository;
import com.dju.labflow.run.metric.application.RunMetricCommand;
import com.dju.labflow.run.metric.domain.*;
import com.dju.labflow.run.metric.domain.repository.RunMetricRepository;
import com.dju.labflow.run.parameter.application.RunParameterCommand;
import com.dju.labflow.run.parameter.domain.*;
import com.dju.labflow.run.parameter.domain.repository.RunParameterRepository;
import com.dju.labflow.run.run.domain.*;
import com.dju.labflow.run.run.exception.RunStateConflictException;
import com.dju.labflow.run.systemmetric.application.RunSystemMetricCommand;
import com.dju.labflow.run.systemmetric.domain.*;
import com.dju.labflow.run.systemmetric.domain.repository.RunSystemMetricRepository;
import com.dju.labflow.run.text.application.RunTextCommand;
import com.dju.labflow.run.text.domain.*;
import com.dju.labflow.run.text.domain.repository.RunTextRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 실행 중인 같은 프로젝트의 기록만 추가한다. 부모 행 잠금으로 종료와 기록 추가의 경합을 막는다. */
@Service
@Transactional
public class RunRecordService {
    private final RunAccessPolicy runAccessPolicy;
    private final RunParameterRepository runParameterRepository;
    private final RunMetricRepository runMetricRepository;
    private final RunLogRepository runLogRepository;
    private final RunTextRepository runTextRepository;
    private final RunSystemMetricRepository runSystemMetricRepository;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final RequestValidator requestValidator;

    public RunRecordService(
            RunAccessPolicy runAccessPolicy,
            RunParameterRepository runParameterRepository,
            RunMetricRepository runMetricRepository,
            RunLogRepository runLogRepository,
            RunTextRepository runTextRepository,
            RunSystemMetricRepository runSystemMetricRepository,
            IdGenerator idGenerator,
            Clock clock,
            RequestValidator requestValidator) {
        this.runAccessPolicy = runAccessPolicy;
        this.runParameterRepository = runParameterRepository;
        this.runMetricRepository = runMetricRepository;
        this.runLogRepository = runLogRepository;
        this.runTextRepository = runTextRepository;
        this.runSystemMetricRepository = runSystemMetricRepository;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.requestValidator = requestValidator;
    }

    public List<RunParameterId> parameters(
            ProjectApiKeyPrincipal principal, RunId runId, RunParameterCommand command) {
        requestValidator.validate(command);
        requireRunning(principal, runId);
        var ids = new ArrayList<RunParameterId>();
        var existingParameters =
                runParameterRepository.findByRunId(runId).stream()
                        .collect(Collectors.toMap(RunParameter::getKey, Function.identity()));
        long newKeys =
                command.parameters().keySet().stream()
                        .filter(key -> !existingParameters.containsKey(key))
                        .count();
        if (existingParameters.size() + newKeys > 100)
            throw new IllegalArgumentException("Run parameters exceed 100 entries");
        Instant now = clock.instant();
        for (var entry : command.parameters().entrySet()) {
            var existing = Optional.ofNullable(existingParameters.get(entry.getKey()));
            if (existing.isPresent()) {
                if (!existing.get().getValue().equals(entry.getValue()))
                    throw new RunStateConflictException("Parameter value cannot be changed");
                ids.add(existing.get().getId());
            } else {
                var record =
                        RunParameter.create(
                                RunParameterId.of(idGenerator.nextId()),
                                runId,
                                entry.getKey(),
                                entry.getValue(),
                                now);
                ids.add(runParameterRepository.save(record).getId());
            }
        }
        return List.copyOf(ids);
    }

    public List<RunMetricId> metrics(
            ProjectApiKeyPrincipal principal, RunId runId, RunMetricCommand command) {
        requestValidator.validate(command);
        requireRunning(principal, runId);
        var ids = new ArrayList<RunMetricId>();
        Instant recordedAt = recordedAt(command.recordedAt());
        for (var entry : command.metrics().entrySet()) {
            var record =
                    RunMetric.create(
                            RunMetricId.of(idGenerator.nextId()),
                            runId,
                            entry.getKey(),
                            entry.getValue(),
                            command.epoch(),
                            command.step(),
                            command.elapsedTime(),
                            recordedAt);
            ids.add(runMetricRepository.save(record).getId());
        }
        return List.copyOf(ids);
    }

    public RunLogId log(ProjectApiKeyPrincipal principal, RunId runId, RunLogCommand command) {
        requestValidator.validate(command);
        requireRunning(principal, runId);
        return runLogRepository
                .save(
                        RunLog.create(
                                RunLogId.of(idGenerator.nextId()),
                                runId,
                                command.message(),
                                command.level(),
                                command.source(),
                                recordedAt(command.recordedAt())))
                .getId();
    }

    public RunTextId text(ProjectApiKeyPrincipal principal, RunId runId, RunTextCommand command) {
        requestValidator.validate(command);
        requireRunning(principal, runId);
        return runTextRepository
                .save(
                        RunText.create(
                                RunTextId.of(idGenerator.nextId()),
                                runId,
                                command.key(),
                                command.text(),
                                command.step(),
                                recordedAt(command.recordedAt())))
                .getId();
    }

    public RunSystemMetricId systemMetric(
            ProjectApiKeyPrincipal principal, RunId runId, RunSystemMetricCommand command) {
        requestValidator.validate(command);
        requireRunning(principal, runId);
        return runSystemMetricRepository
                .save(
                        RunSystemMetric.create(
                                RunSystemMetricId.of(idGenerator.nextId()),
                                runId,
                                command.cpuUtilization(),
                                command.ramUsed(),
                                command.ramTotal(),
                                command.gpus(),
                                command.elapsedTime(),
                                recordedAt(command.recordedAt())))
                .getId();
    }

    private void requireRunning(ProjectApiKeyPrincipal principal, RunId runId) {
        runAccessPolicy.requireOwnedForUpdate(principal, runId).requireRunning();
    }

    private Instant recordedAt(Instant supplied) {
        return supplied == null ? clock.instant() : supplied;
    }
}
