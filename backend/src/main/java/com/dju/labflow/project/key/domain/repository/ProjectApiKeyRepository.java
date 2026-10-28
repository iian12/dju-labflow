package com.dju.labflow.project.key.domain.repository;

import com.dju.labflow.project.key.domain.ProjectApiKey;

import java.util.Optional;

public interface ProjectApiKeyRepository {

    ProjectApiKey save(ProjectApiKey projectApiKey);

    Optional<ProjectApiKey> findByKeyHash(String keyHash);
}
