package com.dju.labflow.project.key.domain;

import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.user.domain.UserId;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;
import com.dju.labflow.global.validation.InputValidation;

/** 프로젝트 API 키의 식별 정보와 폐기 상태를 관리한다. 키 원문은 보관하지 않는다. */
@Getter
public class ProjectApiKey {
    private final ProjectApiKeyId id;
    private final ProjectId projectId;
    private final UserId createdBy;
    private String name;
    private final String keyPrefix;
    private final String keyHash;
    private ProjectApiKeyStatus status;
    private final Instant createdAt;
    private Instant revokedAt;
    private Instant lastUsedAt;

    private ProjectApiKey(ProjectApiKeyId id, ProjectId projectId, UserId createdBy, String name, String keyPrefix, String keyHash, ProjectApiKeyStatus status, Instant createdAt, Instant revokedAt, Instant lastUsedAt) {
        this.id = InputValidation.required(id, "API key ID");
        this.projectId = InputValidation.required(projectId, "Project ID");
        this.createdBy = InputValidation.required(createdBy, "Key owner");
        this.name = InputValidation.text(name, "API key name", 100);
        this.keyPrefix = InputValidation.text(keyPrefix, "Key prefix", 32);
        this.keyHash = InputValidation.text(keyHash, "Key hash", 64);
        this.status = InputValidation.required(status, "Key status");
        this.createdAt = InputValidation.required(createdAt, "Creation time");
        this.revokedAt = revokedAt;
        this.lastUsedAt = lastUsedAt;
    }

    public static ProjectApiKey create(ProjectApiKeyId id, ProjectId projectId, UserId createdBy, String name, String keyPrefix, String keyHash, ProjectApiKeyStatus status, Instant createdAt, Instant revokedAt, Instant lastUsedAt) {
        return new ProjectApiKey(id, projectId, createdBy, name, keyPrefix, keyHash, status, createdAt, revokedAt, lastUsedAt);
    }

    public static ProjectApiKey restore(ProjectApiKeyId id, ProjectId projectId, UserId createdBy, String name, String keyPrefix, String keyHash, ProjectApiKeyStatus status, Instant createdAt, Instant revokedAt, Instant lastUsedAt) {
        return new ProjectApiKey(id, projectId, createdBy, name, keyPrefix, keyHash, status, createdAt, revokedAt, lastUsedAt);
    }

    public void revoke(Instant revokedAt) {
        Objects.requireNonNull(revokedAt, "revokedAt must not be null");

        if (isRevoked()) {
            throw new IllegalStateException("API key is already revoked");
        }

        this.revokedAt = revokedAt;
        this.status = ProjectApiKeyStatus.REVOKED;
    }

    public void validateUsable() {
        if (status != ProjectApiKeyStatus.ACTIVE || isRevoked()) {
            throw new IllegalStateException("API key is not active");
        }
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
