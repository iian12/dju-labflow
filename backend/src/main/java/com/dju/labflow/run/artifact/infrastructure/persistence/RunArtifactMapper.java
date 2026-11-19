package com.dju.labflow.run.artifact.infrastructure.persistence;

import com.dju.labflow.run.artifact.domain.*;
import com.dju.labflow.run.run.domain.RunId;

/** 파일 메타데이터와 업로드 상태, 조회 버전을 보존하는 변환. */
public final class RunArtifactMapper {
    private RunArtifactMapper() {}

    public static RunArtifactEntity toEntity(RunArtifact artifact) {
        return RunArtifactEntity.builder()
                .id(artifact.getId().value())
                .runId(artifact.getRunId().value())
                .name(artifact.getName())
                .kind(artifact.getKind())
                .contentType(artifact.getContentType())
                .expectedSizeBytes(artifact.getExpectedSizeBytes())
                .sizeBytes(artifact.getSizeBytes())
                .sha256(artifact.getSha256())
                .bucket(artifact.getBucket())
                .objectKey(artifact.getObjectKey())
                .status(artifact.getStatus())
                .step(artifact.getStep())
                .clientRequestId(artifact.getClientRequestId())
                .createdAt(artifact.getCreatedAt())
                .uploadExpiresAt(artifact.getUploadExpiresAt())
                .uploadedAt(artifact.getUploadedAt())
                .build();
    }

    public static RunArtifact toDomain(RunArtifactEntity entity) {
        return RunArtifact.restore(
                RunArtifactId.of(entity.getId()),
                RunId.of(entity.getRunId()),
                entity.getName(),
                entity.getKind(),
                entity.getContentType(),
                entity.getExpectedSizeBytes(),
                entity.getSizeBytes(),
                entity.getSha256(),
                entity.getBucket(),
                entity.getObjectKey(),
                entity.getStatus(),
                entity.getStep(),
                entity.getClientRequestId(),
                entity.getCreatedAt(),
                entity.getUploadExpiresAt(),
                entity.getUploadedAt(),
                entity.getVersion());
    }
}
