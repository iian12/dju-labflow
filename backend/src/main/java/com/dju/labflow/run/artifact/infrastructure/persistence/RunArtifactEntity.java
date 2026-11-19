package com.dju.labflow.run.artifact.infrastructure.persistence;

import com.dju.labflow.global.id.AssignedIdEntity;
import com.dju.labflow.run.artifact.domain.*;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

/** 실행 결과 파일의 저장 모델. 예약 정보는 불변이며 검증 결과와 상태만 갱신한다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "run_artifact_entity",
        indexes = {
            @Index(name = "idx_run_artifact_time", columnList = "run_id,created_at,id"),
            @Index(
                    name = "idx_run_artifact_status_time",
                    columnList = "run_id,status,created_at,id"),
            @Index(name = "idx_run_artifact_expiry", columnList = "status,upload_expires_at,id")
        },
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_run_artifact_request",
                    columnNames = {"run_id", "client_request_id"}),
            @UniqueConstraint(
                    name = "uk_run_artifact_object",
                    columnNames = {"bucket", "object_key"})
        })
public class RunArtifactEntity extends AssignedIdEntity {
    @Version private Long version;

    @Column(name = "run_id", nullable = false, updatable = false)
    private Long runId;

    @Column(nullable = false, updatable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private ArtifactKind kind;

    @Column(nullable = false, updatable = false, length = 255)
    private String contentType;

    @Column(nullable = false, updatable = false)
    private Long expectedSizeBytes;

    private Long sizeBytes;

    @Column(length = 64)
    private String sha256;

    @Column(nullable = false, updatable = false, length = 255)
    private String bucket;

    @Column(name = "object_key", nullable = false, updatable = false, length = 512)
    private String objectKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ArtifactStatus status;

    @Column(updatable = false)
    private Long step;

    @Column(name = "client_request_id", nullable = false, updatable = false, length = 64)
    private String clientRequestId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false, updatable = false)
    private Instant uploadExpiresAt;

    private Instant uploadedAt;

    @Builder
    public RunArtifactEntity(
            Long id,
            Long runId,
            String name,
            ArtifactKind kind,
            String contentType,
            Long expectedSizeBytes,
            Long sizeBytes,
            String sha256,
            String bucket,
            String objectKey,
            ArtifactStatus status,
            Long step,
            String clientRequestId,
            Instant createdAt,
            Instant uploadExpiresAt,
            Instant uploadedAt) {
        this.id = id;
        this.runId = runId;
        this.name = name;
        this.kind = kind;
        this.contentType = contentType;
        this.expectedSizeBytes = expectedSizeBytes;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.bucket = bucket;
        this.objectKey = objectKey;
        this.status = status;
        this.step = step;
        this.clientRequestId = clientRequestId;
        this.createdAt = createdAt;
        this.uploadExpiresAt = uploadExpiresAt;
        this.uploadedAt = uploadedAt;
    }

    public void update(ArtifactStatus status, Long sizeBytes, String sha256, Instant uploadedAt) {
        this.status = status;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.uploadedAt = uploadedAt;
    }
}
