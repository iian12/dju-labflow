package com.dju.labflow.project.project.application;

import com.dju.labflow.auth.application.AccountAccessPolicy;
import com.dju.labflow.global.id.IdGenerator;
import com.dju.labflow.global.validation.InputValidation;
import com.dju.labflow.global.validation.RequestValidator;
import com.dju.labflow.project.project.domain.Project;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.project.project.domain.ProjectPage;
import com.dju.labflow.project.project.domain.ProjectVisibility;
import com.dju.labflow.project.project.domain.repository.ProjectContentsRepository;
import com.dju.labflow.project.project.domain.repository.ProjectRepository;
import com.dju.labflow.project.project.exception.ProjectNotFoundException;
import com.dju.labflow.user.domain.User;
import com.dju.labflow.user.domain.UserId;
import com.dju.labflow.user.domain.repository.UserRepository;
import com.dju.labflow.user.exception.UserNotFoundException;
import java.time.Clock;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 개인 프로젝트 CRUD. 공개 조회, 소유자 관리 권한, 버전 충돌과 삭제 트랜잭션을 조정한다. */
@Service
@Transactional(readOnly = true)
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectContentsRepository projectContentsRepository;
    private final UserRepository userRepository;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final RequestValidator requestValidator;

    public ProjectService(
            ProjectRepository projectRepository,
            ProjectContentsRepository projectContentsRepository,
            UserRepository userRepository,
            IdGenerator idGenerator,
            Clock clock,
            RequestValidator requestValidator) {
        this.projectRepository = projectRepository;
        this.projectContentsRepository = projectContentsRepository;
        this.userRepository = userRepository;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.requestValidator = requestValidator;
    }

    @Transactional
    public ProjectId create(UserId id, ProjectCreateCommand command) {
        requestValidator.validate(command);

        User user = requireActiveUser(id);
        Project project =
                Project.create(
                        ProjectId.of(idGenerator.nextId()),
                        command.name(),
                        command.description(),
                        UserId.of(user.getId().value()),
                        command.visibility(),
                        clock.instant());

        Project savedProject = projectRepository.save(project);

        return savedProject.getId();
    }

    public ProjectPage list(UserId requesterId, ProjectListQuery query) {
        requestValidator.validate(query);
        requireActiveUser(requesterId);
        return projectRepository.findByCreatedBy(requesterId, query.page(), query.size());
    }

    public Project get(@Nullable UserId requesterId, ProjectId projectId) {
        Project project =
                projectRepository
                        .findById(InputValidation.required(projectId, "Project ID"))
                        .orElseThrow(ProjectNotFoundException::new);
        if (project.getVisibility() == ProjectVisibility.PRIVATE
                && !project.getCreatedBy().equals(requesterId)) {
            if (requesterId == null)
                throw new AuthenticationCredentialsNotFoundException("Authentication required");
            throw new AccessDeniedException("Private project is only visible to its owner");
        }
        return project;
    }

    @Transactional
    public void update(UserId requesterId, ProjectId projectId, ProjectUpdateCommand command) {
        requestValidator.validate(command);
        Project project = ownedForUpdate(requesterId, projectId);
        project.requireVersion(command.version());
        project.updateDetails(
                command.name(), command.description(), command.visibility(), clock.instant());
        projectRepository.update(project);
    }

    @Transactional
    public void delete(UserId requesterId, ProjectId projectId, Long version) {
        Project project = ownedForUpdate(requesterId, projectId);
        project.requireVersion(version);
        projectContentsRepository.deleteByProjectId(projectId);
        projectRepository.delete(project);
    }

    private Project ownedForUpdate(UserId requesterId, ProjectId projectId) {
        requireActiveUser(requesterId);
        Project project =
                projectRepository
                        .findByIdForUpdate(InputValidation.required(projectId, "Project ID"))
                        .orElseThrow(ProjectNotFoundException::new);
        if (!project.getCreatedBy().equals(requesterId))
            throw new AccessDeniedException("Only the project owner can manage the project");
        return project;
    }

    private User requireActiveUser(UserId id) {
        User user =
                userRepository
                        .findById(InputValidation.required(id, "User ID"))
                        .orElseThrow(UserNotFoundException::new);
        AccountAccessPolicy.requireActive(user);
        return user;
    }
}
