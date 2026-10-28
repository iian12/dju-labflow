package com.dju.labflow.project.key.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** SDK 요청에서 키 해시로 저장된 API 키를 조회한다. */
public interface ProjectApiKeyJpaRepository extends JpaRepository<ProjectApiKeyEntity, Long> {

    Optional<ProjectApiKeyEntity> findByKeyHash(String keyHash);
}
