package com.dju.labflow.run.artifact.application;

import com.dju.labflow.global.id.IdGenerator;
import com.dju.labflow.project.key.domain.ProjectApiKeyPrincipal;
import com.dju.labflow.project.project.exception.ProjectNotFoundException;
import com.dju.labflow.run.artifact.domain.*;
import com.dju.labflow.run.artifact.domain.repository.RunArtifactRepository;
import com.dju.labflow.run.run.application.RunAccessPolicy;
import com.dju.labflow.run.run.domain.Run;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.run.run.exception.RunNotFoundException;
import com.dju.labflow.run.run.exception.RunStateConflictException;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Only reservation and publication hold project/run locks; no request stream is read here. */
@Service
@Transactional
public class LocalArtifactTransactions {
    private final RunAccessPolicy access;
    private final RunArtifactRepository artifacts;
    private final EntityManager em;
    private final IdGenerator ids;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public LocalArtifactTransactions(
            RunAccessPolicy access,
            RunArtifactRepository artifacts,
            EntityManager em,
            IdGenerator ids,
            Clock clock,
            ApplicationEventPublisher events) {
        this.access = access;
        this.artifacts = artifacts;
        this.em = em;
        this.ids = ids;
        this.clock = clock;
        this.events = events;
    }

    public RunArtifact reserve(
            ProjectApiKeyPrincipal principal,
            RunId runId,
            String name,
            ArtifactKind kind,
            String requestId,
            long length,
            long projectLimit) {
        Run run = access.requireOwnedForUpdate(principal, runId);
        // Recover reservations abandoned by a crashed process before accounting for capacity.
        List<Long> expired =
                em.createQuery(
                                "select a.id from RunArtifactEntity a where a.bucket = 'local' "
                                        + "and a.status = :status and a.uploadExpiresAt <= :now "
                                        + "and a.runId in (select r.id from RunEntity r where r.projectId = :project)",
                                Long.class)
                        .setParameter("status", ArtifactStatus.UPLOADING)
                        .setParameter("now", clock.instant())
                        .setParameter("project", principal.projectId().value())
                        .getResultList();
        if (!expired.isEmpty()) {
            em.createQuery("delete from RunArtifactEntity a where a.id in :ids")
                    .setParameter("ids", expired)
                    .executeUpdate();
            events.publishEvent(new LocalArtifactService.LocalFilesDeleted(expired));
        }
        Optional<RunArtifact> previous = artifacts.findByRunIdAndClientRequestId(runId, requestId);
        if (previous.isPresent()) {
            RunArtifact old = previous.get();
            if (old.getStatus() == ArtifactStatus.READY
                    && old.getName().equals(name)
                    && old.getKind() == kind
                    && old.getSizeBytes() == length) return old;
            throw new RunStateConflictException(
                    "Artifact request ID is in progress or has different content");
        }
        run.requireRunning();
        Number used =
                (Number)
                        em.createQuery(
                                        "select coalesce(sum(case when a.status = :uploading "
                                                + "then a.expectedSizeBytes else a.sizeBytes end), 0) from RunArtifactEntity a "
                                                + "where a.status in (:uploading, :ready) and a.runId in "
                                                + "(select r.id from RunEntity r where r.projectId = :project)")
                                .setParameter("uploading", ArtifactStatus.UPLOADING)
                                .setParameter("ready", ArtifactStatus.READY)
                                .setParameter("project", principal.projectId().value())
                                .getSingleResult();
        if (length > projectLimit - used.longValue())
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "Project storage limit exceeded");
        RunArtifactId id = RunArtifactId.of(ids.nextId());
        Instant now = clock.instant();
        return artifacts.save(
                RunArtifact.create(
                        id,
                        runId,
                        name,
                        kind,
                        "application/octet-stream",
                        length,
                        "local",
                        id.value().toString(),
                        null,
                        requestId,
                        now,
                        now.plusSeconds(86400)));
    }

    public RunArtifact complete(
            ProjectApiKeyPrincipal principal, RunArtifact reserved, String sha256) {
        Run run = access.requireOwnedForUpdate(principal, reserved.getRunId());
        RunArtifact artifact =
                artifacts
                        .findById(reserved.getId())
                        .orElseThrow(
                                () ->
                                        new RunStateConflictException(
                                                "Upload reservation no longer exists"));
        if (reserved.getStatus() == ArtifactStatus.READY) {
            if (artifact.getStatus() == ArtifactStatus.READY && sha256.equals(artifact.getSha256()))
                return artifact;
            throw new RunStateConflictException(
                    "Artifact request ID already used with different content");
        }
        run.requireRunning();
        artifact.completeUpload(reserved.getExpectedSizeBytes(), sha256, clock.instant());
        artifacts.update(artifact);
        return artifact;
    }

    public void discard(ProjectApiKeyPrincipal principal, RunArtifact reserved) {
        try {
            access.requireOwnedForUpdate(principal, reserved.getRunId());
        } catch (ProjectNotFoundException | RunNotFoundException deleted) {
            return;
        }
        em.createQuery("delete from RunArtifactEntity a where a.id = :id and a.status = :status")
                .setParameter("id", reserved.getId().value())
                .setParameter("status", ArtifactStatus.UPLOADING)
                .executeUpdate();
    }
}
