package com.dju.labflow.project.key.infrastructure.persistence;

import com.dju.labflow.project.key.domain.ProjectApiKey;
import com.dju.labflow.project.key.domain.repository.ProjectApiKeyRepository;
import com.dju.labflow.project.key.exception.ProjectApiKeyNotFoundException;
import java.util.Objects;
import java.util.Optional;
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
}
