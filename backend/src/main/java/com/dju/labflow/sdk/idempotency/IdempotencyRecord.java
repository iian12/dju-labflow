package com.dju.labflow.sdk.idempotency;

import com.dju.labflow.global.schema.SchemaSince;
import com.dju.labflow.project.key.infrastructure.persistence.ProjectApiKeyEntity;
import com.dju.labflow.project.project.infrastructure.persistence.ProjectEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

/** 원문 API 키/멱등키를 보관하지 않는 15일 응답 원장. */
@Entity
@SchemaSince(3)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "idempotency_request",
        uniqueConstraints =
                @UniqueConstraint(name = "uk_idempotency_scope", columnNames = "scope_hash"),
        indexes = {
            @Index(name = "idx_idempotency_expiry", columnList = "expires_at"),
            @Index(name = "idx_idempotency_project", columnList = "project_id"),
            @Index(name = "idx_idempotency_api_key", columnList = "api_key_id")
        })
public class IdempotencyRecord {
    @Id private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(name = "api_key_id", nullable = false, updatable = false)
    private Long apiKeyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "project_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_idempotency_project"))
    private ProjectEntity project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "api_key_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_idempotency_api_key"))
    private ProjectApiKeyEntity apiKey;

    @Column(nullable = false, updatable = false, length = 64)
    private String scopeHash;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Column(nullable = false, length = 36)
    private String ownerToken;

    public enum State {
        PROCESSING,
        COMPLETED
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private State state;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private Instant processingUntil;

    private Integer responseStatus;

    @Column(length = 4096)
    private String responseHeaders;

    @Column(columnDefinition = "LONGTEXT")
    private String responseBody;

    @Column(length = 64)
    private String bodySha256;

    @Column(unique = true)
    private Long artifactId;

    void renew(String token, String requestFingerprint, Instant now) {
        ownerToken = token;
        fingerprint = requestFingerprint;
        state = State.PROCESSING;
        createdAt = now;
        expiresAt = now.plus(java.time.Duration.ofDays(15));
        processingUntil = now.plusSeconds(120);
        responseStatus = null;
        responseHeaders = null;
        responseBody = null;
        bodySha256 = null;
        artifactId = null;
    }

    void resume(Instant now) {
        processingUntil = now.plusSeconds(120);
    }

    void bindArtifact(Long id) {
        artifactId = id;
    }

    void retryAfterInterruption(Instant now) {
        processingUntil = now.minusSeconds(1);
    }

    void complete(IdempotencyStore.Response response) {
        state = State.COMPLETED;
        responseStatus = response.status();
        responseHeaders = response.headers();
        responseBody = response.body();
        bodySha256 = response.sha256();
    }
}
