package com.dju.labflow.sdk.request.infrastructure;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 프로젝트별 requestKey 유일성을 DB 제약과 함께 보장한다. */
public interface SdkRequestJpaRepository extends JpaRepository<SdkRequestEntity, Long> {
    Optional<SdkRequestEntity> findByProjectIdAndRequestKey(Long projectId, String requestKey);
}
