package com.dju.labflow.project.key.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProjectApiKeyJpaRepository extends JpaRepository<ProjectApiKeyEntity, Long> {

    Optional<ProjectApiKeyEntity> findByKeyHash(String keyHash);
}
