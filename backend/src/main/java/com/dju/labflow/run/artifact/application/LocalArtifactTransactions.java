package com.dju.labflow.run.artifact.application;

import com.dju.labflow.global.id.IdGenerator;
import com.dju.labflow.project.key.domain.ProjectApiKeyPrincipal;
import com.dju.labflow.project.project.exception.ProjectNotFoundException;
import com.dju.labflow.run.artifact.domain.*;
import com.dju.labflow.run.artifact.domain.repository.RunArtifactRepository;
import com.dju.labflow.run.run.application.RunAccessPolicy;
import com.dju.labflow.run.run.domain.*;
import com.dju.labflow.run.run.exception.RunNotFoundException;
import com.dju.labflow.run.run.exception.RunStateConflictException;
import com.dju.labflow.sdk.idempotency.*;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** 프로젝트 → Run → 멱등 원장 순서로 잠근다. 파일 본문은 이 트랜잭션에서 읽지 않는다. */
@Service
@Transactional
public class LocalArtifactTransactions {
    private final RunAccessPolicy access;
    private final RunArtifactRepository artifacts;
    private final EntityManager em;
    private final IdGenerator ids;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final IdempotencyStore ledger;
    private final JsonMapper json;

    public LocalArtifactTransactions(
            RunAccessPolicy access,
            RunArtifactRepository artifacts,
            EntityManager em,
            IdGenerator ids,
            Clock clock,
            ApplicationEventPublisher events,
            IdempotencyStore ledger,
            JsonMapper json) {
        this.access = access;
        this.artifacts = artifacts;
        this.em = em;
        this.ids = ids;
        this.clock = clock;
        this.events = events;
        this.ledger = ledger;
        this.json = json;
    }

    public RunArtifact reserve(
            ProjectApiKeyPrincipal principal,
            RunId runId,
            String name,
            ArtifactKind kind,
            IdempotencyStore.Claim claim,
            long length,
            long projectLimit) {
        Run run = access.requireOwnedForUpdate(principal, runId);
        List<Long> expired =
                em.createQuery(
                                "select a.id from RunArtifactEntity a where a.bucket='local' and a.status=:status and a.uploadExpiresAt<=:now and a.runId in (select r.id from RunEntity r where r.projectId=:project)",
                                Long.class)
                        .setParameter("status", ArtifactStatus.UPLOADING)
                        .setParameter("now", clock.instant())
                        .setParameter("project", principal.projectId().value())
                        .getResultList();
        if (!expired.isEmpty()) {
            for (Long id : expired) ledger.clearArtifact(id);
            em.createQuery("delete from RunArtifactEntity a where a.id in :ids")
                    .setParameter("ids", expired)
                    .executeUpdate();
            events.publishEvent(new LocalArtifactService.LocalFilesDeleted(expired));
        }
        Long bound = ledger.boundArtifact(claim);
        if (bound != null) {
            Optional<RunArtifact> previous = artifacts.findById(RunArtifactId.of(bound));
            if (previous.isPresent()) {
                RunArtifact old = previous.get();
                if (!old.getRunId().equals(runId)
                        || !old.getName().equals(name)
                        || old.getKind() != kind
                        || old.getExpectedSizeBytes() != length)
                    throw IdempotencyException.mismatch();
                if (old.getStatus() == ArtifactStatus.READY) return old;
                run.requireRunning();
                return old;
            }
            ledger.clearArtifact(bound);
        }
        run.requireRunning();
        Number used =
                (Number)
                        em.createQuery(
                                        "select coalesce(sum(case when a.status=:uploading then a.expectedSizeBytes else a.sizeBytes end),0) from RunArtifactEntity a where a.status in (:uploading,:ready) and a.runId in (select r.id from RunEntity r where r.projectId=:project)")
                                .setParameter("uploading", ArtifactStatus.UPLOADING)
                                .setParameter("ready", ArtifactStatus.READY)
                                .setParameter("project", principal.projectId().value())
                                .getSingleResult();
        if (length > projectLimit - used.longValue())
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "Project storage limit exceeded");
        RunArtifactId id = RunArtifactId.of(ids.nextId());
        Instant now = clock.instant();
        RunArtifact result =
                artifacts.save(
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
                                now,
                                now.plusSeconds(86400)));
        ledger.bindArtifact(claim, id.value());
        return result;
    }

    public RunArtifact complete(
            ProjectApiKeyPrincipal principal,
            RunArtifact reserved,
            String sha256,
            IdempotencyStore.Claim claim) {
        Run run = access.requireOwnedForUpdate(principal, reserved.getRunId());
        RunArtifact artifact =
                artifacts
                        .findById(reserved.getId())
                        .orElseThrow(
                                () ->
                                        new RunStateConflictException(
                                                "Upload reservation no longer exists"));
        ledger.boundArtifact(claim);
        if (artifact.getStatus() == ArtifactStatus.READY) {
            if (!sha256.equals(artifact.getSha256())) throw IdempotencyException.mismatch();
        } else {
            run.requireRunning();
            artifact.completeUpload(reserved.getExpectedSizeBytes(), sha256, clock.instant());
            artifacts.update(artifact);
        }
        ledger.complete(
                claim,
                new IdempotencyStore.Response(
                        201,
                        json.writeValueAsString(Map.of("Content-Type", "application/json")),
                        json.writeValueAsString(Map.of("artifactId", artifact.getId().value())),
                        sha256));
        return artifact;
    }

    @Transactional(readOnly = true)
    public Optional<RunArtifact> committedArtifact(
            RunArtifact reserved, IdempotencyStore.Claim claim) {
        if (ledger.completed(claim) == null) return Optional.empty();
        return artifacts
                .findById(reserved.getId())
                .filter(artifact -> artifact.getStatus() == ArtifactStatus.READY);
    }

    public void discard(ProjectApiKeyPrincipal principal, RunArtifact reserved) {
        try {
            access.requireOwnedForUpdate(principal, reserved.getRunId());
        } catch (ProjectNotFoundException | RunNotFoundException deleted) {
            return;
        }
        RunArtifact current = artifacts.findById(reserved.getId()).orElse(null);
        if (current != null && current.getStatus() == ArtifactStatus.UPLOADING) {
            ledger.clearArtifact(reserved.getId().value());
            em.createQuery("delete from RunArtifactEntity a where a.id=:id and a.status=:status")
                    .setParameter("id", reserved.getId().value())
                    .setParameter("status", ArtifactStatus.UPLOADING)
                    .executeUpdate();
        }
    }
}
