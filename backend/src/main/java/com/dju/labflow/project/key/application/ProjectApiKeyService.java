package com.dju.labflow.project.key.application;

import com.dju.labflow.global.id.IdGenerator;
import com.dju.labflow.global.validation.RequestValidator;
import com.dju.labflow.project.key.domain.ProjectApiKey;
import com.dju.labflow.project.key.domain.ProjectApiKeyId;
import com.dju.labflow.project.key.domain.ProjectApiKeyStatus;
import com.dju.labflow.project.key.domain.repository.ProjectApiKeyRepository;
import com.dju.labflow.project.key.exception.ProjectApiKeyNotFoundException;
import com.dju.labflow.project.key.util.ProjectApiKeyGenerator;
import com.dju.labflow.project.key.util.ProjectApiKeyHasher;
import com.dju.labflow.project.project.domain.Project;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.project.project.domain.repository.ProjectRepository;
import com.dju.labflow.project.project.exception.ProjectNotFoundException;
import com.dju.labflow.user.domain.UserId;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** 프로젝트 소유권을 확인하고 API 키를 생성해 해시만 저장한다. */
@Service
public class ProjectApiKeyService {

    private final ProjectRepository projectRepository;
    private final ProjectApiKeyGenerator projectApiKeyGenerator;
    private final Clock clock;
    private final IdGenerator idGenerator;
    private final ProjectApiKeyHasher projectApiKeyHasher;
    private final ProjectApiKeyRepository projectApiKeyRepository;
    private final RequestValidator requestValidator;

    public ProjectApiKeyService(
            ProjectRepository projectRepository,
            ProjectApiKeyGenerator projectApiKeyGenerator,
            Clock clock,
            IdGenerator idGenerator,
            ProjectApiKeyHasher projectApiKeyHasher,
            ProjectApiKeyRepository projectApiKeyRepository,
            RequestValidator requestValidator) {
        this.projectRepository = projectRepository;
        this.projectApiKeyGenerator = projectApiKeyGenerator;
        this.clock = clock;
        this.idGenerator = idGenerator;
        this.projectApiKeyHasher = projectApiKeyHasher;
        this.projectApiKeyRepository = projectApiKeyRepository;
        this.requestValidator = requestValidator;
    }

    @Transactional
    public IssueProjectApiKeyResult issue(
            UserId requesterId, ProjectId projectId, IssueProjectApiKeyCommand command) {
        requestValidator.validate(command);
        Project project =
                projectRepository
                        .findByIdForUpdate(projectId)
                        .orElseThrow(ProjectNotFoundException::new);
        if (!project.getCreatedBy().equals(requesterId)) {
            throw new AccessDeniedException("Only the project owner can issue API keys");
        }

        GeneratedProjectApiKey generated = projectApiKeyGenerator.generate();

        Instant now = clock.instant();

        ProjectApiKey apiKey =
                ProjectApiKey.create(
                        ProjectApiKeyId.of(idGenerator.nextId()),
                        projectId,
                        requesterId,
                        command.name(),
                        generated.keyPrefix(),
                        projectApiKeyHasher.hash(generated.rawKey()),
                        ProjectApiKeyStatus.ACTIVE,
                        now,
                        null,
                        null);

        projectApiKeyRepository.save(apiKey);

        return new IssueProjectApiKeyResult(apiKey.getId(), generated.rawKey());
    }

    public record KeyPage(
            List<ProjectApiKey> items, int page, int size, long totalElements, long totalPages) {}

    @Transactional
    public KeyPage list(UserId requester, ProjectId projectId, int page, int size) {
        if (page < 0 || size < 1 || size > 100)
            throw new IllegalArgumentException("Invalid key page");
        Project project =
                projectRepository.findById(projectId).orElseThrow(ProjectNotFoundException::new);
        if (!project.getCreatedBy().equals(requester))
            throw new AccessDeniedException("Only owner can read keys");
        long count = projectApiKeyRepository.countByProjectId(projectId);
        return new KeyPage(
                projectApiKeyRepository.findByProjectId(projectId, page, size),
                page,
                size,
                count,
                (count + size - 1) / size);
    }

    @Transactional
    public void revoke(UserId requester, ProjectId projectId, ProjectApiKeyId keyId) {
        Project project =
                projectRepository
                        .findByIdForUpdate(projectId)
                        .orElseThrow(ProjectNotFoundException::new);
        if (!project.getCreatedBy().equals(requester))
            throw new AccessDeniedException("Only owner can revoke keys");
        ProjectApiKey key =
                projectApiKeyRepository
                        .findById(keyId)
                        .filter(value -> value.getProjectId().equals(projectId))
                        .orElseThrow(ProjectApiKeyNotFoundException::new);
        if (!key.isRevoked()) {
            key.revoke(clock.instant());
            projectApiKeyRepository.update(key);
        }
    }
}
