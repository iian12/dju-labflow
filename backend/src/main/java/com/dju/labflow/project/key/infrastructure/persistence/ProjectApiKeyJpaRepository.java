package com.dju.labflow.project.key.infrastructure.persistence;

import com.dju.labflow.project.key.domain.ProjectApiKeyStatus;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** SDK 요청에서 키 해시로 저장된 API 키를 조회한다. */
public interface ProjectApiKeyJpaRepository extends JpaRepository<ProjectApiKeyEntity, Long> {

    Optional<ProjectApiKeyEntity> findByKeyHash(String keyHash);

    /** 상태를 덮어쓰지 않고 사용 시각을 기록하며, 이전에 읽은 관리용 객체는 무효화한다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            """
            update ProjectApiKeyEntity k
            set k.lastUsedAt = case
                    when k.lastUsedAt is null or k.lastUsedAt < :usedAt then :usedAt
                    else k.lastUsedAt end,
                k.version = k.version + 1
            where k.id = :id and k.status = :activeStatus and k.revokedAt is null
            """)
    int recordUsage(
            @Param("id") Long id,
            @Param("usedAt") Instant usedAt,
            @Param("activeStatus") ProjectApiKeyStatus activeStatus);
}
