package com.dju.labflow.run.run.application;

import com.dju.labflow.auth.application.AccountAccessPolicy;
import com.dju.labflow.project.project.domain.Project;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.project.project.domain.repository.ProjectRepository;
import com.dju.labflow.project.project.exception.ProjectNotFoundException;
import com.dju.labflow.run.run.domain.Run;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.run.run.domain.RunStatus;
import com.dju.labflow.run.run.domain.repository.RunRepository;
import com.dju.labflow.run.run.exception.RunNotFoundException;
import com.dju.labflow.user.domain.UserId;
import com.dju.labflow.user.domain.repository.UserRepository;
import com.dju.labflow.user.exception.UserNotFoundException;
import java.time.Clock;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 로그인한 소유자의 수동 종료. 프로젝트 → 실행 잠금 순서를 SDK와 공유한다. */
@Service
public class RunManagementService {
    public static final String STOP_REASON = "Manually stopped by project owner";
    private final ProjectRepository projects;
    private final RunRepository runs;
    private final UserRepository users;
    private final Clock clock;

    public RunManagementService(
            ProjectRepository projects, RunRepository runs, UserRepository users, Clock clock) {
        this.projects = projects;
        this.runs = runs;
        this.users = users;
        this.clock = clock;
    }

    @Transactional
    public void stop(UserId user, ProjectId projectId, RunId runId) {
        AccountAccessPolicy.requireActive(
                users.findById(user).orElseThrow(UserNotFoundException::new));
        Project project =
                projects.findByIdForUpdate(projectId).orElseThrow(ProjectNotFoundException::new);
        if (!project.getCreatedBy().equals(user))
            throw new AccessDeniedException("Only the project owner can stop runs");
        Run run =
                runs.findByIdForUpdate(runId)
                        .filter(candidate -> candidate.getProjectId().equals(projectId))
                        .orElseThrow(RunNotFoundException::new);
        RunStatus previous = run.getStatus();
        run.fail(STOP_REASON, clock.instant());
        if (previous != run.getStatus()) runs.update(run);
    }
}
