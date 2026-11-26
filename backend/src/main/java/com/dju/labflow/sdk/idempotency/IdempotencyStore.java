package com.dju.labflow.sdk.idempotency;

import com.dju.labflow.global.id.IdGenerator;
import com.dju.labflow.project.key.domain.ProjectApiKeyPrincipal;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 짧은 요청 선점과, 실제 쓰기 트랜잭션에 참여하는 응답 저장을 분리한다. */
@Service
public class IdempotencyStore {
    private final EntityManager em;
    private final IdGenerator ids;
    private final Clock clock;

    public IdempotencyStore(EntityManager em, IdGenerator ids, Clock clock) {
        this.em = em;
        this.ids = ids;
        this.clock = clock;
    }

    public record Response(int status, String headers, String body, String sha256) {}

    public record Claim(long id, String ownerToken, Response response) {
        public boolean replay() {
            return response != null;
        }
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 5)
    public Claim claim(ProjectApiKeyPrincipal principal, String scope, String fingerprint) {
        Instant now = clock.instant();
        IdempotencyRecord existing = find(scope);
        if (existing == null) {
            String token = UUID.randomUUID().toString();
            em.createNativeQuery(
                            """
                insert ignore into idempotency_request
                (id,project_id,api_key_id,scope_hash,fingerprint,owner_token,state,created_at,expires_at,processing_until)
                values (:id,:project,:key,:scope,:fingerprint,:token,'PROCESSING',:now,:expires,:until)
                """)
                    .setParameter("id", ids.nextId())
                    .setParameter("project", principal.projectId().value())
                    .setParameter("key", principal.apiKeyId().value())
                    .setParameter("scope", scope)
                    .setParameter("fingerprint", fingerprint)
                    .setParameter("token", token)
                    .setParameter("now", now)
                    .setParameter("expires", now.plus(Duration.ofDays(15)))
                    .setParameter("until", now.plusSeconds(120))
                    .executeUpdate();
            em.clear();
            existing = find(scope);
            if (existing == null)
                throw new IdempotencyException(
                        503, "IDEMPOTENCY_UNAVAILABLE", "Cannot claim idempotent request");
            if (existing.getOwnerToken().equals(token)) return snapshot(existing);
        }
        if (existing.getExpiresAt().isAfter(now)) {
            if (!existing.getFingerprint().equals(fingerprint))
                throw IdempotencyException.mismatch();
            if (existing.getState() == IdempotencyRecord.State.COMPLETED) return snapshot(existing);
            if (existing.getProcessingUntil().isAfter(now)) throw IdempotencyException.processing();
        }
        // JSON writes hold this row lock; binary uploads use a separate filesystem lock.
        // NOWAIT prevents takeover of a slow request when its recovery lease elapses.
        try {
            em.refresh(
                    existing,
                    LockModeType.PESSIMISTIC_WRITE,
                    Map.of("jakarta.persistence.lock.timeout", 0));
        } catch (PessimisticLockException | LockTimeoutException failure) {
            throw IdempotencyException.processing();
        }
        if (!existing.getExpiresAt().isAfter(now))
            existing.renew(UUID.randomUUID().toString(), fingerprint, now);
        else {
            if (!existing.getFingerprint().equals(fingerprint))
                throw IdempotencyException.mismatch();
            if (existing.getState() == IdempotencyRecord.State.COMPLETED) return snapshot(existing);
            if (existing.getProcessingUntil().isAfter(now)) throw IdempotencyException.processing();
            // Keep the reservation identity when recovering a crashed owner.
            existing.resume(now);
        }
        return snapshot(existing);
    }

    private IdempotencyRecord find(String scope) {
        return em.createQuery(
                        "select r from IdempotencyRecord r where r.scopeHash=:scope",
                        IdempotencyRecord.class)
                .setParameter("scope", scope)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    private Claim snapshot(IdempotencyRecord row) {
        Response response =
                row.getState() == IdempotencyRecord.State.COMPLETED
                        ? new Response(
                                row.getResponseStatus(),
                                row.getResponseHeaders(),
                                row.getResponseBody(),
                                row.getBodySha256())
                        : null;
        return new Claim(row.getId(), row.getOwnerToken(), response);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(Claim claim) {
        IdempotencyRecord row =
                em.find(IdempotencyRecord.class, claim.id(), LockModeType.PESSIMISTIC_WRITE);
        if (row == null
                || !row.getOwnerToken().equals(claim.ownerToken())
                || row.getState() != IdempotencyRecord.State.PROCESSING)
            throw IdempotencyException.processing();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Long boundArtifact(Claim claim) {
        IdempotencyRecord row =
                em.find(IdempotencyRecord.class, claim.id(), LockModeType.PESSIMISTIC_WRITE);
        if (row == null || !row.getOwnerToken().equals(claim.ownerToken()))
            throw IdempotencyException.processing();
        return row.getArtifactId();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void bindArtifact(Claim claim, Long artifactId) {
        lock(claim);
        em.find(IdempotencyRecord.class, claim.id()).bindArtifact(artifactId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void clearArtifact(Long artifactId) {
        em.createQuery(
                        "update IdempotencyRecord r set r.artifactId=null where r.artifactId=:artifact")
                .setParameter("artifact", artifactId)
                .executeUpdate();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(Claim claim, Response response) {
        IdempotencyRecord row = em.find(IdempotencyRecord.class, claim.id());
        if (row == null || !row.getOwnerToken().equals(claim.ownerToken()))
            throw IdempotencyException.processing();
        row.complete(response);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeFailure(Claim claim, Response response) {
        lock(claim);
        complete(claim, response);
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            readOnly = true,
            isolation = Isolation.READ_COMMITTED)
    public Response completed(Claim claim) {
        IdempotencyRecord row = em.find(IdempotencyRecord.class, claim.id());
        return row != null
                        && row.getOwnerToken().equals(claim.ownerToken())
                        && row.getState() == IdempotencyRecord.State.COMPLETED
                ? snapshot(row).response()
                : null;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void interrupted(Claim claim) {
        IdempotencyRecord row =
                em.find(IdempotencyRecord.class, claim.id(), LockModeType.PESSIMISTIC_WRITE);
        if (row != null
                && row.getOwnerToken().equals(claim.ownerToken())
                && row.getState() == IdempotencyRecord.State.PROCESSING)
            row.retryAfterInterruption(clock.instant());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public int deleteExpired() {
        List<Long> expired =
                em.createQuery(
                                "select r.id from IdempotencyRecord r where r.expiresAt<=:now order by r.expiresAt",
                                Long.class)
                        .setParameter("now", clock.instant())
                        .setMaxResults(1000)
                        .getResultList();
        if (expired.isEmpty()) return 0;
        return em.createQuery(
                        "delete from IdempotencyRecord r where r.id in :ids and r.expiresAt<=:now")
                .setParameter("ids", expired)
                .setParameter("now", clock.instant())
                .executeUpdate();
    }
}
