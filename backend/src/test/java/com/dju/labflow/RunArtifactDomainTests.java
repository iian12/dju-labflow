package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;

import com.dju.labflow.run.artifact.domain.*;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.run.run.exception.RunStateConflictException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 업로드 검증 실패의 원자성, 재시도 및 종료 상태의 불변식을 검사한다. */
class RunArtifactDomainTests {
    private static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");

    @Test
    void rejectsSizeMismatchAndExpiryWithoutPartiallyPublishingTheFile() {
        RunArtifact artifact = artifact(10L, NOW.plusSeconds(60));
        assertThatThrownBy(() -> artifact.completeUpload(9L, null, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> artifact.completeUpload(10L, "invalid", NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> artifact.completeUpload(10L, null, NOW.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> artifact.completeUpload(10L, null, NOW.plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(artifact.getStatus()).isEqualTo(ArtifactStatus.UPLOADING);
        assertThat(artifact.getSizeBytes()).isNull();
        assertThat(artifact.getSha256()).isNull();
        assertThat(artifact.getUploadedAt()).isNull();
    }

    @Test
    void completionRetryPreservesOriginalTimeAndRejectsReplacement() {
        RunArtifact artifact = artifact(10L, NOW.plusSeconds(60));
        artifact.completeUpload(10L, "A".repeat(64), NOW.plusSeconds(1));
        artifact.completeUpload(10L, "a".repeat(64), NOW.plusSeconds(120));
        assertThat(artifact.getStatus()).isEqualTo(ArtifactStatus.READY);
        assertThat(artifact.getSha256()).isEqualTo("a".repeat(64));
        assertThat(artifact.getUploadedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThatThrownBy(() -> artifact.completeUpload(11L, "a".repeat(64), NOW.plusSeconds(2)))
                .isInstanceOf(RunStateConflictException.class);
        assertThatThrownBy(() -> artifact.completeUpload(10L, "b".repeat(64), NOW.plusSeconds(2)))
                .isInstanceOf(RunStateConflictException.class);
        assertThatThrownBy(artifact::failUpload).isInstanceOf(RunStateConflictException.class);
        artifact.requestDeletion();
        artifact.requestDeletion();
        assertThatThrownBy(() -> artifact.completeUpload(10L, "a".repeat(64), NOW.plusSeconds(2)))
                .isInstanceOf(RunStateConflictException.class);
    }

    @Test
    void failedUploadsCannotBecomeReadyButCanBeMarkedForCleanup() {
        RunArtifact artifact = artifact(0L, NOW.plusSeconds(60));
        artifact.failUpload();
        artifact.failUpload();
        assertThatThrownBy(() -> artifact.completeUpload(0L, null, NOW.plusSeconds(1)))
                .isInstanceOf(RunStateConflictException.class);
        artifact.requestDeletion();
        assertThat(artifact.getStatus()).isEqualTo(ArtifactStatus.DELETING);
        assertThat(artifact.getSizeBytes()).isNull();
        assertThat(artifact.getUploadedAt()).isNull();
    }

    @Test
    void rejectsInvalidReservationAndInconsistentRestoredState() {
        assertThatThrownBy(() -> artifact(-1L, NOW.plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> artifact(10L, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                RunArtifact.restore(
                                        RunArtifactId.of(1L),
                                        RunId.of(1L),
                                        "model.pt",
                                        ArtifactKind.MODEL,
                                        "application/octet-stream",
                                        10L,
                                        null,
                                        null,
                                        "files",
                                        "runs/1/artifacts/1/content",
                                        ArtifactStatus.READY,
                                        1L,
                                        NOW,
                                        NOW.plusSeconds(60),
                                        null,
                                        0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                RunArtifact.restore(
                                        RunArtifactId.of(1L),
                                        RunId.of(1L),
                                        "model.pt",
                                        ArtifactKind.MODEL,
                                        "application/octet-stream",
                                        10L,
                                        11L,
                                        null,
                                        "files",
                                        "runs/1/artifacts/1/content",
                                        ArtifactStatus.READY,
                                        1L,
                                        NOW,
                                        NOW.plusSeconds(60),
                                        NOW.plusSeconds(1),
                                        0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RunArtifact artifact(Long size, Instant expiresAt) {
        return RunArtifact.create(
                RunArtifactId.of(1L),
                RunId.of(1L),
                "model.pt",
                ArtifactKind.MODEL,
                "application/octet-stream",
                size,
                "files",
                "runs/1/artifacts/1/content",
                1L,
                NOW,
                expiresAt);
    }
}
