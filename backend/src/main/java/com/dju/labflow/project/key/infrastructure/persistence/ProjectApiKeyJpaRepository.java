package com.dju.labflow.project.key.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** SDK 요청에서 키 해시로 저장된 API 키를 조회한다. */
public interface ProjectApiKeyJpaRepository extends JpaRepository<ProjectApiKeyEntity, Long> {

    Optional<ProjectApiKeyEntity> findByKeyHash(String keyHash);
}
