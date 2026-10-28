package com.dju.labflow.project.key.infrastructure.persistence;

import com.dju.labflow.project.key.domain.ProjectApiKey;
import com.dju.labflow.project.key.domain.repository.ProjectApiKeyRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.dju.labflow.project.key.exception.ProjectApiKeyNotFoundException;

import java.util.Objects;
import java.util.Optional;

/** API 키 도메인 저장소를 Spring Data JPA에 연결하는 어댑터. */
@Repository
public class ProjectApiKeyRepositoryImpl implements ProjectApiKeyRepository {

    private final ProjectApiKeyJpaRepository jpaRepository;

    public ProjectApiKeyRepositoryImpl(ProjectApiKeyJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public ProjectApiKey save(ProjectApiKey projectApiKey) {
        Objects.requireNonNull(projectApiKey, "ProjectApiKey must not be null");

        ProjectApiKeyEntity savedEntity = jpaRepository.save(ProjectApiKeyMapper.toEntity(projectApiKey));

        return ProjectApiKeyMapper.toDomain(savedEntity);
    }

    @Override
    @Transactional
    public void update(ProjectApiKey projectApiKey) {
        Objects.requireNonNull(projectApiKey, "ProjectApiKey must not be null");
        ProjectApiKeyEntity entity = jpaRepository.findById(projectApiKey.getId().value())
                .orElseThrow(ProjectApiKeyNotFoundException::new);
        entity.update(projectApiKey.getName(), projectApiKey.getStatus(),
                projectApiKey.getRevokedAt(), projectApiKey.getLastUsedAt());
    }

    @Override
    public Optional<ProjectApiKey> findByKeyHash(String keyHash) {
        Objects.requireNonNull(keyHash, "Key hash must not be null");

        return jpaRepository.findByKeyHash(keyHash).map(ProjectApiKeyMapper::toDomain);
    }
}
