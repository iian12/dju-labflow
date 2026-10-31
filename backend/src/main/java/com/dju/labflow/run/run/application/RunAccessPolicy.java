package com.dju.labflow.run.run.application;

import com.dju.labflow.global.validation.InputValidation;
import com.dju.labflow.project.key.domain.ProjectApiKeyPrincipal;
import com.dju.labflow.run.run.domain.Run;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.run.run.domain.repository.RunRepository;
import com.dju.labflow.run.run.exception.RunNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** SDK 인증 주체의 프로젝트와 실행의 소속을 확인한다. PUBLIC도 교차 프로젝트 쓰기를 허용하지 않는다. */
@Component
public class RunAccessPolicy {
    private final RunRepository runRepository;

    public RunAccessPolicy(RunRepository runRepository) {
        this.runRepository = runRepository;
    }

    public Run requireOwned(ProjectApiKeyPrincipal principal, RunId runId) {
        InputValidation.required(principal, "SDK principal");
        return check(
                principal,
                runRepository
                        .findById(InputValidation.required(runId, "Run ID"))
                        .orElseThrow(RunNotFoundException::new));
    }

    public Run requireOwnedForUpdate(ProjectApiKeyPrincipal principal, RunId runId) {
        InputValidation.required(principal, "SDK principal");
        return check(
                principal,
                runRepository
                        .findByIdForUpdate(InputValidation.required(runId, "Run ID"))
                        .orElseThrow(RunNotFoundException::new));
    }

    private Run check(ProjectApiKeyPrincipal principal, Run run) {
        if (!run.getProjectId().equals(principal.projectId())
                || !run.getCreatedBy().equals(principal.createdBy()))
            throw new AccessDeniedException("Run belongs to another project");
        return run;
    }
}
