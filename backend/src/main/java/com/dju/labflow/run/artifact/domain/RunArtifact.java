package com.dju.labflow.run.artifact.domain;

import com.dju.labflow.global.validation.InputValidation;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.run.run.domain.RunValidation;
import com.dju.labflow.run.run.exception.RunStateConflictException;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import lombok.Getter;

/** 실행 결과 파일의 저장 위치와 업로드 수명주기. 파일 본문과 임시 URL은 보관하지 않는다. */
@Getter
public class RunArtifact {
    private final RunArtifactId id;
    private final RunId runId;
    private final String name;
    private final ArtifactKind kind;
    private final String contentType;
    private final Long expectedSizeBytes;
    private Long sizeBytes;
    private String sha256;
    private final String bucket;
    private final String objectKey;
    private ArtifactStatus status;
    private final Long step;
    private final String clientRequestId;
    private final Instant createdAt;
    private final Instant uploadExpiresAt;
    private Instant uploadedAt;
    private final Long version;

    private RunArtifact(
            RunArtifactId id,
            RunId runId,
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
            Instant uploadedAt,
            Long version) {
        this.id = InputValidation.required(id, "RunArtifact ID");
        this.runId = InputValidation.required(runId, "Run ID");
        this.name = InputValidation.text(name, "Artifact name", 255);
        this.kind = InputValidation.required(kind, "Artifact kind");
        this.contentType = InputValidation.text(contentType, "Content type", 255);
        this.expectedSizeBytes =
                RunValidation.nonNegative(
                        InputValidation.required(expectedSizeBytes, "Expected size"),
                        "Expected size");
        this.sizeBytes = RunValidation.nonNegative(sizeBytes, "File size");
        this.sha256 = checksum(sha256);
        this.bucket = InputValidation.text(bucket, "Bucket", 255);
        this.objectKey = InputValidation.text(objectKey, "Object key", 512);
        this.status = InputValidation.required(status, "Artifact status");
        this.step = RunValidation.nonNegative(step, "Step");
        this.clientRequestId = InputValidation.text(clientRequestId, "Client request ID", 64);
        this.createdAt = InputValidation.required(createdAt, "Creation time");
        this.uploadExpiresAt = InputValidation.required(uploadExpiresAt, "Upload expiry time");
        this.uploadedAt = uploadedAt;
        this.version = version;
        validateState();
    }

    public static RunArtifact create(
            RunArtifactId id,
            RunId runId,
            String name,
            ArtifactKind kind,
            String contentType,
            Long expectedSizeBytes,
            String bucket,
            String objectKey,
            Long step,
            String clientRequestId,
            Instant createdAt,
            Instant uploadExpiresAt) {
        return new RunArtifact(
                id,
                runId,
                name,
                kind,
                contentType,
                expectedSizeBytes,
                null,
                null,
                bucket,
                objectKey,
                ArtifactStatus.UPLOADING,
                step,
                clientRequestId,
                createdAt,
                uploadExpiresAt,
                null,
                null);
    }

    public static RunArtifact restore(
            RunArtifactId id,
            RunId runId,
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
            Instant uploadedAt,
            Long version) {
        return new RunArtifact(
                id,
                runId,
                name,
                kind,
                contentType,
                expectedSizeBytes,
                sizeBytes,
                sha256,
                bucket,
                objectKey,
                status,
                step,
                clientRequestId,
                createdAt,
                uploadExpiresAt,
                uploadedAt,
                InputValidation.required(version, "Artifact version"));
    }

    /** 스토리지에서 확인한 크기와 체크섬만 전달한다. 실패 시 기존 상태를 변경하지 않는다. */
    public void completeUpload(Long actualSizeBytes, String verifiedSha256, Instant now) {
        RunValidation.nonNegative(
                InputValidation.required(actualSizeBytes, "File size"), "File size");
        String verified = checksum(verifiedSha256);
        if (status == ArtifactStatus.READY) {
            if (Objects.equals(sizeBytes, actualSizeBytes) && Objects.equals(sha256, verified))
                return;
            throw new RunStateConflictException("Uploaded artifact cannot be replaced");
        }
        requireUploading();
        InputValidation.required(now, "Upload time");
        if (now.isBefore(createdAt) || !now.isBefore(uploadExpiresAt))
            throw new IllegalArgumentException("Upload time is outside the reservation window");
        if (!expectedSizeBytes.equals(actualSizeBytes))
            throw new IllegalArgumentException("File size differs from the reserved size");
        this.sizeBytes = actualSizeBytes;
        this.sha256 = verified;
        this.uploadedAt = now;
        this.status = ArtifactStatus.READY;
    }

    public void failUpload() {
        if (status == ArtifactStatus.FAILED) return;
        requireUploading();
        this.status = ArtifactStatus.FAILED;
    }

    /** 실제 객체를 제거하기 전 목록과 다운로드에서 숨긴다. */
    public void requestDeletion() {
        this.status = ArtifactStatus.DELETING;
    }

    private void requireUploading() {
        if (status != ArtifactStatus.UPLOADING)
            throw new RunStateConflictException("Artifact is not uploading");
    }

    private void validateState() {
        if (!uploadExpiresAt.isAfter(createdAt))
            throw new IllegalArgumentException("Upload expiry must follow creation time");
        boolean uploaded = uploadedAt != null;
        if (uploaded != (sizeBytes != null) || (!uploaded && sha256 != null))
            throw new IllegalArgumentException("Uploaded file metadata is inconsistent");
        if (status == ArtifactStatus.READY && !uploaded)
            throw new IllegalArgumentException("Ready artifacts require uploaded file metadata");
        if ((status == ArtifactStatus.UPLOADING || status == ArtifactStatus.FAILED) && uploaded)
            throw new IllegalArgumentException(
                    "Unfinished artifacts cannot have uploaded metadata");
        if (uploaded
                && (!expectedSizeBytes.equals(sizeBytes)
                        || uploadedAt.isBefore(createdAt)
                        || !uploadedAt.isBefore(uploadExpiresAt)))
            throw new IllegalArgumentException("Uploaded metadata differs from the reservation");
    }

    private static String checksum(String value) {
        if (value == null) return null;
        if (!value.matches("[0-9a-fA-F]{64}"))
            throw new IllegalArgumentException("SHA-256 must contain 64 hexadecimal characters");
        return value.toLowerCase(Locale.ROOT);
    }
}
