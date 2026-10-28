package com.dju.labflow.project.project.application;

import com.dju.labflow.global.id.IdGenerator;
import com.dju.labflow.project.project.ProjectCreateCommand;
import com.dju.labflow.project.project.domain.Project;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.project.project.domain.repository.ProjectRepository;
import com.dju.labflow.user.domain.User;
import com.dju.labflow.user.domain.UserId;
import com.dju.labflow.user.domain.repository.UserRepository;
import com.dju.labflow.user.exception.UserNotFoundException;
import com.dju.labflow.global.validation.RequestValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/** 프로젝트 생성 시 사용자 존재를 확인하고 ID와 생성 시각을 부여한다. */
@Slf4j
@Service
@Transactional(readOnly = true)
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final RequestValidator requestValidator;

    public ProjectService(ProjectRepository projectRepository, UserRepository userRepository, IdGenerator idGenerator, Clock clock, RequestValidator requestValidator) {
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.requestValidator = requestValidator;
    }

    @Transactional
    public ProjectId create(UserId id, ProjectCreateCommand command) {
        requestValidator.validate(command);

        log.info("Project creation started: userid={}", id.value());
        User user = userRepository.findById(id)
                .orElseThrow(UserNotFoundException::new);


        log.info("User found: userId=({})", user.getId().value());
        Project project = Project.create(
                ProjectId.of(idGenerator.nextId()),
                command.name(),
                command.description(),
                UserId.of(user.getId().value()),
                command.visibility(),
                Instant.now(clock)
        );
        log.info("Project created in memory: projectId={}", project.getId().value());

        Project savedProject = projectRepository.save(project);
        log.info("Project saved: projectId={}", savedProject.getId().value());


        return savedProject.getId();
    }
}
