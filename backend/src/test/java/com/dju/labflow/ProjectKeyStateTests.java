package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;

import com.dju.labflow.project.key.application.IssueProjectApiKeyCommand;
import com.dju.labflow.project.key.application.ProjectApiKeyAuthenticationService;
import com.dju.labflow.project.key.application.ProjectApiKeyService;
import com.dju.labflow.project.key.domain.*;
import com.dju.labflow.project.key.domain.repository.ProjectApiKeyRepository;
import com.dju.labflow.project.key.exception.InvalidProjectApiKeyException;
import com.dju.labflow.project.key.util.ProjectApiKeyHasher;
import com.dju.labflow.project.project.domain.*;
import com.dju.labflow.project.project.domain.repository.ProjectRepository;
import com.dju.labflow.user.domain.*;
import com.dju.labflow.user.domain.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 별도 트랜잭션의 오래된 객체, 키 폐기 및 SDK 인증의 실제 DB 동작을 검증한다. */
@SpringBootTest
@ActiveProfiles("test")
class ProjectKeyStateTests {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final AtomicLong IDS = new AtomicLong(90000L);
    private final List<Long> ownerIds = new ArrayList<>();
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired ProjectApiKeyRepository keys;
    @Autowired ProjectApiKeyService issuance;
    @Autowired ProjectApiKeyAuthenticationService authentication;
    @Autowired ProjectApiKeyHasher hasher;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;

    @AfterEach
    void cleanCommittedFixtures() {
        if (ownerIds.isEmpty()) return;
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            em.createQuery(
                                            "delete from ProjectApiKeyEntity k where k.createdBy in :ids")
                                    .setParameter("ids", ownerIds)
                                    .executeUpdate();
                            em.createQuery("delete from ProjectEntity p where p.createdBy in :ids")
                                    .setParameter("ids", ownerIds)
                                    .executeUpdate();
                            em.createQuery("delete from UserEntity u where u.id in :ids")
                                    .setParameter("ids", ownerIds)
                                    .executeUpdate();
                        });
    }

    @Test
    void staleProjectCannotUndoPrivateVisibilityAcrossTransactions() {
        Project p = fixture(AccountStatus.ACTIVE);
        Project stale = projects.findById(p.getId()).orElseThrow();
        Project latest = projects.findById(p.getId()).orElseThrow();
        latest.updateDetails("private", null, ProjectVisibility.PRIVATE, NOW.plusSeconds(10));
        projects.update(latest);
        stale.updateDetails("renamed", null, ProjectVisibility.PUBLIC, NOW.plusSeconds(20));

        assertThatThrownBy(() -> projects.update(stale))
                .isInstanceOf(OptimisticLockingFailureException.class);
        Project saved = projects.findById(p.getId()).orElseThrow();
        assertThat(saved.getVisibility()).isEqualTo(ProjectVisibility.PRIVATE);
        assertThat(saved.getName()).isEqualTo("private");
        saved.updateDetails("fresh edit", null, ProjectVisibility.PRIVATE, NOW.plusSeconds(30));
        projects.update(saved);
        assertThat(projects.findById(p.getId()).orElseThrow().getName()).isEqualTo("fresh edit");
    }

    @Test
    void objectWithoutPersistedVersionCannotOverwriteExistingProject() {
        Project p = fixture(AccountStatus.ACTIVE);
        Project versionless =
                Project.create(
                        p.getId(),
                        "overwrite",
                        null,
                        p.getCreatedBy(),
                        ProjectVisibility.PRIVATE,
                        NOW);
        assertThatThrownBy(() -> projects.update(versionless))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(projects.findById(p.getId()).orElseThrow().getVisibility())
                .isEqualTo(ProjectVisibility.PUBLIC);
    }

    @Test
    void staleKeyCannotReactivateRevokedKeyAcrossTransactions() {
        ProjectApiKey k = keys.save(key(fixture(AccountStatus.ACTIVE)));
        ProjectApiKey stale = keys.findByKeyHash(k.getKeyHash()).orElseThrow();
        ProjectApiKey latest = keys.findByKeyHash(k.getKeyHash()).orElseThrow();
        latest.revoke(NOW.plusSeconds(10));
        keys.update(latest);

        assertThatThrownBy(() -> keys.update(stale))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThatThrownBy(() -> authentication.authenticate(raw(k.getProjectId())))
                .isInstanceOf(InvalidProjectApiKeyException.class);
        assertThat(keys.findByKeyHash(k.getKeyHash()).orElseThrow().getStatus())
                .isEqualTo(ProjectApiKeyStatus.REVOKED);
    }

    @Test
    void issuedKeyIsUnusedUntilSuccessfulAuthentication() {
        Project p = fixture(AccountStatus.ACTIVE);
        var issued =
                issuance.issue(p.getCreatedBy(), p.getId(), new IssueProjectApiKeyCommand("sdk"));
        String hash = hasher.hash(issued.rawApiKey());
        assertThat(keys.findByKeyHash(hash).orElseThrow().getLastUsedAt()).isNull();

        Instant before = Instant.now();
        assertThat(authentication.authenticate(issued.rawApiKey()).projectId())
                .isEqualTo(p.getId());
        Instant firstUse = keys.findByKeyHash(hash).orElseThrow().getLastUsedAt();
        // DB는 마이크로초 정밀도로 저장할 수 있다.
        assertThat(firstUse).isBetween(before.minusMillis(1), Instant.now().plusMillis(1));
        authentication.authenticate(issued.rawApiKey());
        assertThat(keys.findByKeyHash(hash).orElseThrow().getLastUsedAt())
                .isAfterOrEqualTo(firstUse);
    }

    @ParameterizedTest
    @EnumSource(
            value = AccountStatus.class,
            names = {"BLOCKED", "PENDING_VERIFICATION"})
    void inactiveOwnersDoNotLeaveUsageTimestamp(AccountStatus status) {
        ProjectApiKey k = keys.save(key(fixture(status)));
        assertThatThrownBy(() -> authentication.authenticate(raw(k.getProjectId())))
                .isInstanceOf(InvalidProjectApiKeyException.class);
        assertThat(keys.findByKeyHash(k.getKeyHash()).orElseThrow().getLastUsedAt()).isNull();
    }

    @Test
    void usageRecordingCannotUpdateOrReactivateRevokedKey() {
        ProjectApiKey k = keys.save(key(fixture(AccountStatus.ACTIVE)));
        k.revoke(NOW.plusSeconds(10));
        keys.update(k);
        assertThat(keys.recordUsage(k.getId(), NOW.plusSeconds(20))).isFalse();
        ProjectApiKey saved = keys.findByKeyHash(k.getKeyHash()).orElseThrow();
        assertThat(saved.getLastUsedAt()).isNull();
        assertThat(saved.getStatus()).isEqualTo(ProjectApiKeyStatus.REVOKED);
    }

    @Test
    void delayedUsageCannotMoveTimestampBackwardsOrBeErasedByStaleSnapshot() {
        ProjectApiKey k = keys.save(key(fixture(AccountStatus.ACTIVE)));
        assertThat(keys.recordUsage(k.getId(), NOW.plusSeconds(20))).isTrue();
        assertThat(keys.recordUsage(k.getId(), NOW.plusSeconds(10))).isTrue();
        assertThatThrownBy(() -> keys.update(k))
                .isInstanceOf(OptimisticLockingFailureException.class);
        ProjectApiKey saved = keys.findByKeyHash(k.getKeyHash()).orElseThrow();
        assertThat(saved.getLastUsedAt()).isEqualTo(NOW.plusSeconds(20));
        saved.revoke(NOW.plusSeconds(30));
        keys.update(saved);
        assertThat(keys.findByKeyHash(k.getKeyHash()).orElseThrow().getLastUsedAt())
                .isEqualTo(NOW.plusSeconds(20));
    }

    @Test
    void concurrentAuthenticationsDoNotFailOnUsageVersionConflicts() throws Exception {
        ProjectApiKey k = keys.save(key(fixture(AccountStatus.ACTIVE)));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Future<ProjectApiKeyPrincipal>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                calls.add(
                        executor.submit(
                                () -> {
                                    if (!start.await(10, TimeUnit.SECONDS))
                                        throw new IllegalStateException("timeout");
                                    return authentication.authenticate(raw(k.getProjectId()));
                                }));
            }
            start.countDown();
            for (var call : calls) {
                assertThat(call.get(10, TimeUnit.SECONDS).projectId()).isEqualTo(k.getProjectId());
            }
        }
        assertThat(keys.findByKeyHash(k.getKeyHash()).orElseThrow().getLastUsedAt()).isNotNull();
    }

    private Project fixture(AccountStatus status) {
        long id = IDS.incrementAndGet();
        ownerIds.add(id);
        User u =
                users.save(
                        User.restore(
                                UserId.of(id),
                                "state" + id + "@example.test",
                                "encoded",
                                "test",
                                Role.USER,
                                status));
        return projects.save(
                Project.create(
                        ProjectId.of(id), "test", null, u.getId(), ProjectVisibility.PUBLIC, NOW));
    }

    private ProjectApiKey key(Project p) {
        return ProjectApiKey.create(
                ProjectApiKeyId.of(p.getId().value()),
                p.getId(),
                p.getCreatedBy(),
                "sdk",
                "lfp_test",
                hasher.hash(raw(p.getId())),
                ProjectApiKeyStatus.ACTIVE,
                NOW,
                null,
                null);
    }

    private String raw(ProjectId id) {
        return "test-key-" + id.value();
    }
}
