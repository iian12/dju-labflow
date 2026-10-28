package com.dju.labflow.project.key.infrastructure.persistence;

import com.dju.labflow.project.key.domain.ProjectApiKey;
import com.dju.labflow.project.key.domain.repository.ProjectApiKeyRepository;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.Optional;

@Repository
public class ProjectApiKeyRepositoryImpl implements ProjectApiKeyRepository {

    private final ProjectApiKeyJpaRepository jpaRepository;

    public ProjectApiKeyRepositoryImpl(ProjectApiKeyJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public ProjectApiKey save(ProjectApiKey projectApiKey) {
        Objects.requireNonNull(projectApiKey, "ProjectApiKey must not be null");

        ProjectApiKeyEntity savedEntity = jpaRepository.save(ProjectApiKeyMapper.toEntity(projectApiKey));

        return ProjectApiKeyMapper.toDomain(savedEntity);
    }

    @Override
    public Optional<ProjectApiKey> findByKeyHash(String keyHash) {
        Objects.requireNonNull(keyHash, "Key hash must not be null");

        return jpaRepository.findByKeyHash(keyHash).map(ProjectApiKeyMapper::toDomain);
    }
}
