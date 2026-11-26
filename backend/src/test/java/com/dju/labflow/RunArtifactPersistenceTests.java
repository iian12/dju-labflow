package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;

import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.project.project.domain.repository.ProjectContentsRepository;
import com.dju.labflow.run.artifact.domain.*;
import com.dju.labflow.run.artifact.domain.repository.RunArtifactRepository;
import com.dju.labflow.run.run.domain.*;
import com.dju.labflow.run.run.infrastructure.persistence.RunEntity;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** 실제 JPA 저장소의 상태 보존, 유니크 제약, 오래된 갱신과 프로젝트 삭제를 검증한다. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RunArtifactPersistenceTests {
    private static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");
    @Autowired RunArtifactRepository artifacts;
    @Autowired ProjectContentsRepository contents;
    @Autowired EntityManager em;

    @Test
    void persistsLifecycleAndOnlyListsReadyFilesForTheRequestedRun() {
        RunArtifact pending = artifacts.save(artifact(7001L, 701L, "req-1", "object-1"));
        RunArtifact ready = artifacts.save(artifact(7002L, 701L, "req-2", "object-2"));
        ready.completeUpload(10L, "a".repeat(64), NOW.plusSeconds(1));
        artifacts.update(ready);
        artifacts.save(artifact(7003L, 702L, "req-3", "object-3"));
        em.flush();
        em.clear();

        assertThat(pending.getVersion()).isNotNull();
        assertThat(artifacts.findReadyByRunId(RunId.of(701L), 10))
                .singleElement()
                .satisfies(
                        saved -> {
                            assertThat(saved.getId()).isEqualTo(ready.getId());
                            assertThat(saved.getSizeBytes()).isEqualTo(10L);
                            assertThat(saved.getExpectedSizeBytes()).isEqualTo(10L);
                            assertThat(saved.getSha256()).isEqualTo("a".repeat(64));
                            assertThat(saved.getUploadedAt()).isEqualTo(NOW.plusSeconds(1));
                            assertThat(saved.getStep()).isEqualTo(10L);
                        });
        assertThat(artifacts.findByRunId(RunId.of(701L), 1))
                .extracting(RunArtifact::getId)
                .containsExactly(pending.getId());
        assertThatThrownBy(() -> artifacts.findByRunId(RunId.of(701L), 1001))
                .isInstanceOf(InvalidDataAccessApiUsageException.class);
    }

    @Test
    void rejectsAStaleSnapshotAfterCompletionWasPersisted() {
        RunArtifact first = artifacts.save(artifact(7011L, 701L, "req-1", "object-1"));
        RunArtifact stale = artifacts.findById(first.getId()).orElseThrow();
        first.completeUpload(10L, null, NOW.plusSeconds(1));
        artifacts.update(first);
        em.flush();
        em.clear();
        stale.failUpload();
        assertThatThrownBy(() -> artifacts.update(stale))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void artifactsDoNotStoreRequestKeysAndAllowIndependentStorageObjects() {
        artifacts.save(artifact(7021L, 701L, "first", "object-1"));
        artifacts.save(artifact(7022L, 701L, "second", "object-2"));
        em.flush();
        assertThat(artifacts.findByRunId(RunId.of(701L), 100)).hasSize(2);
    }

    @Test
    void rejectsSharingOneStorageObjectBetweenArtifacts() {
        artifacts.save(artifact(7031L, 701L, "req-1", "same-object"));
        em.flush();
        assertThatThrownBy(
                        () -> {
                            artifacts.save(artifact(7032L, 702L, "req-2", "same-object"));
                            em.flush();
                        })
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void projectDeletionRemovesItsArtifactMetadataAndKeepsOtherRuns() {
        em.persist(
                RunEntity.builder()
                        .id(701L)
                        .projectId(77L)
                        .createdBy(77L)
                        .name("run")
                        .metadata(Map.of())
                        .status(RunStatus.RUNNING)
                        .startedAt(NOW)
                        .build());
        RunArtifact removed = artifacts.save(artifact(7041L, 701L, "req-1", "object-1"));
        RunArtifact kept = artifacts.save(artifact(7042L, 702L, "req-2", "object-2"));
        contents.deleteByProjectId(ProjectId.of(77L));
        assertThat(artifacts.findById(removed.getId())).isEmpty();
        assertThat(artifacts.findById(kept.getId())).isPresent();
    }

    private RunArtifact artifact(long id, long runId, String requestId, String objectKey) {
        return RunArtifact.create(
                RunArtifactId.of(id),
                RunId.of(runId),
                "model.pt",
                ArtifactKind.MODEL,
                "application/octet-stream",
                10L,
                "files",
                objectKey,
                10L,
                NOW,
                NOW.plusSeconds(3600));
    }
}
