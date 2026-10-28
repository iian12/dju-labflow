package com.dju.labflow.project.key.infrastructure.persistence;

import com.dju.labflow.global.id.AssignedIdEntity;
import com.dju.labflow.project.key.domain.ProjectApiKeyStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Index;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** API 키의 해시와 관리 정보를 저장하는 JPA 엔티티. 원문 키는 저장하지 않는다. */
@Getter
@Entity
@Table(name = "project_api_key_entity",
        indexes = @Index(name = "idx_project_api_key_project_id", columnList = "project_id"),
        uniqueConstraints = @UniqueConstraint(name = "uk_project_api_key_hash", columnNames = "key_hash"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectApiKeyEntity extends AssignedIdEntity {

    @Version
    private Long version;
    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;
    @Column(nullable = false, updatable = false)
    private Long createdBy;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(nullable = false, updatable = false, length = 32)
    private String keyPrefix;
    @Column(name = "key_hash", nullable = false, updatable = false, length = 64)
    private String keyHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ProjectApiKeyStatus status;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    private Instant revokedAt;
    private Instant lastUsedAt;

    @Builder
    public ProjectApiKeyEntity(Long id, Long projectId, Long createdBy, String name, String keyPrefix, String keyHash, ProjectApiKeyStatus status, Instant createdAt, Instant revokedAt, Instant lastUsedAt) {
        this.id = id;
        this.projectId = projectId;
        this.createdBy = createdBy;
        this.name = name;
        this.keyPrefix = keyPrefix;
        this.keyHash = keyHash;
        this.status = status;
        this.createdAt = createdAt;
        this.revokedAt = revokedAt;
        this.lastUsedAt = lastUsedAt;
    }

    /** 키 원문/해시와 소유 관계는 고정하고 관리 상태만 갱신한다. */
    public void update(String name, ProjectApiKeyStatus status, Instant revokedAt, Instant lastUsedAt) {
        this.name = name;
        this.status = status;
        this.revokedAt = revokedAt;
        this.lastUsedAt = lastUsedAt;
    }
}
