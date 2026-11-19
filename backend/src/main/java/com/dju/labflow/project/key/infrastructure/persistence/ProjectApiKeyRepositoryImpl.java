package com.dju.labflow.project.key.infrastructure.persistence;

import com.dju.labflow.project.key.domain.ProjectApiKey;
import com.dju.labflow.project.key.domain.ProjectApiKeyId;
import com.dju.labflow.project.key.domain.ProjectApiKeyStatus;
import com.dju.labflow.project.key.domain.repository.ProjectApiKeyRepository;
import com.dju.labflow.project.key.exception.ProjectApiKeyNotFoundException;
import com.dju.labflow.project.project.domain.ProjectId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** API 키 도메인 저장소를 Spring Data JPA에 연결하는 어댑터. */
@Repository
public class ProjectApiKeyRepositoryImpl implements ProjectApiKeyRepository {

    private final ProjectApiKeyJpaRepository projectApiKeyJpaRepository;

    public ProjectApiKeyRepositoryImpl(ProjectApiKeyJpaRepository projectApiKeyJpaRepository) {
        this.projectApiKeyJpaRepository = projectApiKeyJpaRepository;
    }

    @Override
    @Transactional
    public ProjectApiKey save(ProjectApiKey projectApiKey) {
        Objects.requireNonNull(projectApiKey, "ProjectApiKey must not be null");

        ProjectApiKeyEntity savedEntity =
                projectApiKeyJpaRepository.save(ProjectApiKeyMapper.toEntity(projectApiKey));

        return ProjectApiKeyMapper.toDomain(savedEntity);
    }

    @Override
    @Transactional
    public void update(ProjectApiKey projectApiKey) {
        Objects.requireNonNull(projectApiKey, "ProjectApiKey must not be null");
        ProjectApiKeyEntity entity =
                projectApiKeyJpaRepository
                        .findById(projectApiKey.getId().value())
                        .orElseThrow(ProjectApiKeyNotFoundException::new);
        if (projectApiKey.getVersion() == null
                || !projectApiKey.getVersion().equals(entity.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(
                    ProjectApiKeyEntity.class, projectApiKey.getId().value());
        }
        entity.update(
                projectApiKey.getName(),
                projectApiKey.getStatus(),
                projectApiKey.getRevokedAt(),
                projectApiKey.getLastUsedAt());
    }

    @Override
    public Optional<ProjectApiKey> findByKeyHash(String keyHash) {
        Objects.requireNonNull(keyHash, "Key hash must not be null");

        return projectApiKeyJpaRepository.findByKeyHash(keyHash).map(ProjectApiKeyMapper::toDomain);
    }

    @Override
    @Transactional
    public boolean recordUsage(ProjectApiKeyId id, Instant usedAt) {
        Objects.requireNonNull(id, "API key ID must not be null");
        Objects.requireNonNull(usedAt, "Usage time must not be null");
        return projectApiKeyJpaRepository.recordUsage(
                        id.value(), usedAt, ProjectApiKeyStatus.ACTIVE)
                == 1;
    }

    @Override
    public Optional<ProjectApiKey> findById(ProjectApiKeyId id) {
        return projectApiKeyJpaRepository.findById(id.value()).map(ProjectApiKeyMapper::toDomain);
    }

    @Override
    public List<ProjectApiKey> findByProjectId(ProjectId projectId, int page, int size) {
        return projectApiKeyJpaRepository
                .findByProjectIdOrderByCreatedAtDescIdDesc(
                        projectId.value(), PageRequest.of(page, size))
                .stream()
                .map(ProjectApiKeyMapper::toDomain)
                .toList();
    }

    @Override
    public long countByProjectId(ProjectId projectId) {
        return projectApiKeyJpaRepository.countByProjectId(projectId.value());
    }
}
