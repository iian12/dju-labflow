package com.dju.labflow.run.run.application;

import com.dju.labflow.global.id.IdGenerator;
import com.dju.labflow.global.validation.InputValidation;
import com.dju.labflow.global.validation.RequestValidator;
import com.dju.labflow.project.key.domain.ProjectApiKeyPrincipal;
import com.dju.labflow.project.project.domain.repository.ProjectRepository;
import com.dju.labflow.project.project.exception.ProjectNotFoundException;
import com.dju.labflow.run.run.domain.*;
import com.dju.labflow.run.run.domain.repository.RunRepository;
import java.time.Clock;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 실행 생성/조회/종료 서비스. 프로젝트와 생성자는 인증된 SDK 키에서 결정한다. */
@Service
@Transactional(readOnly = true)
public class RunService {
    private final RunRepository runRepository;
    private final ProjectRepository projectRepository;
    private final RunAccessPolicy runAccessPolicy;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final RequestValidator requestValidator;

    public RunService(
            RunRepository runRepository,
            ProjectRepository projectRepository,
            RunAccessPolicy runAccessPolicy,
            IdGenerator idGenerator,
            Clock clock,
            RequestValidator requestValidator) {
        this.runRepository = runRepository;
        this.projectRepository = projectRepository;
        this.runAccessPolicy = runAccessPolicy;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.requestValidator = requestValidator;
    }

    @Transactional
    public RunId create(ProjectApiKeyPrincipal principal, RunCreateCommand command) {
        InputValidation.required(principal, "SDK principal");
        requestValidator.validate(command);
        var project =
                projectRepository
                        .findById(principal.projectId())
                        .orElseThrow(ProjectNotFoundException::new);
        if (!project.getCreatedBy().equals(principal.createdBy()))
            throw new AccessDeniedException("Only project owners can create runs");
        return runRepository
                .save(
                        Run.create(
                                RunId.of(idGenerator.nextId()),
                                project.getId(),
                                project.getCreatedBy(),
                                command.name(),
                                command.metadata(),
                                clock.instant()))
                .getId();
    }

    public Run get(ProjectApiKeyPrincipal principal, RunId runId) {
        return runAccessPolicy.requireOwned(principal, runId);
    }

    @Transactional
    public void complete(ProjectApiKeyPrincipal principal, RunId runId) {
        Run run = runAccessPolicy.requireOwnedForUpdate(principal, runId);
        if (run.getStatus() == RunStatus.COMPLETED) return;
        run.complete(clock.instant());
        runRepository.update(run);
    }

    @Transactional
    public void fail(ProjectApiKeyPrincipal principal, RunId runId, RunFailCommand command) {
        requestValidator.validate(command);
        Run run = runAccessPolicy.requireOwnedForUpdate(principal, runId);
        RunStatus previous = run.getStatus();
        run.fail(command.error(), clock.instant());
        if (previous != run.getStatus()) runRepository.update(run);
    }
}
