package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;

import com.dju.labflow.auth.infrastructure.persistence.RefreshTokenEntity;
import com.dju.labflow.global.id.AssignedIdEntity;
import com.dju.labflow.project.key.domain.*;
import com.dju.labflow.project.key.domain.repository.ProjectApiKeyRepository;
import com.dju.labflow.project.key.infrastructure.persistence.ProjectApiKeyEntity;
import com.dju.labflow.project.project.domain.*;
import com.dju.labflow.project.project.infrastructure.persistence.ProjectEntity;
import com.dju.labflow.user.domain.*;
import com.dju.labflow.user.infrastructure.persistence.UserEntity;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** 스키마의 실제 제약·인덱스와 할당 ID 엔티티의 영속성 생명주기를 검증한다. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PersistenceInfrastructureTests {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    @Autowired EntityManager em;
    @Autowired ProjectApiKeyRepository keys;

    @ParameterizedTest
    @ValueSource(strings = {"user", "project", "key", "refresh"})
    void assignedEntityStopsBeingNewAfterPersistAndLoad(String kind) {
        AssignedIdEntity entity =
                switch (kind) {
                    case "user" ->
                            UserEntity.builder()
                                    .id(90L)
                                    .email("lifecycle@example.test")
                                    .name("test")
                                    .encodedPassword("encoded")
                                    .role(Role.USER)
                                    .accountStatus(AccountStatus.ACTIVE)
                                    .build();
                    case "project" ->
                            ProjectEntity.builder()
                                    .id(90L)
                                    .name("test")
                                    .createdBy(1L)
                                    .visibility(ProjectVisibility.PRIVATE)
                                    .createdAt(NOW)
                                    .build();
                    case "key" -> keyEntity(90L, "a".repeat(64));
                    default ->
                            RefreshTokenEntity.builder()
                                    .id(90L)
                                    .userId(1L)
                                    .tokenHash("a".repeat(43))
                                    .expiresAt(NOW.plusSeconds(3600))
                                    .createdAt(NOW)
                                    .build();
                };
        assertThat(entity.isNew()).isTrue();
        em.persist(entity);
        em.flush();
        assertThat(entity.isNew()).isFalse();
        em.clear();
        assertThat(em.find(entity.getClass(), 90L).isNew()).isFalse();
    }

    @Test
    void keyHashCannotBeDuplicatedAcrossKeys() {
        em.persist(keyEntity(91L, "a".repeat(64)));
        em.flush();
        em.persist(keyEntity(92L, "a".repeat(64)));
        assertThatThrownBy(() -> em.flush()).isInstanceOf(RuntimeException.class);
    }

    @Test
    void keyHashAndProjectLookupHaveIndexesAndCorrectColumnConstraints() {
        List<String> indexedColumns = new ArrayList<>();
        List<String> uniqueColumns = new ArrayList<>();
        em.unwrap(Session.class)
                .doWork(
                        connection -> {
                            try (var indexes =
                                    connection
                                            .getMetaData()
                                            .getIndexInfo(
                                                    null,
                                                    null,
                                                    "PROJECT_API_KEY_ENTITY",
                                                    false,
                                                    false)) {
                                while (indexes.next()) {
                                    indexedColumns.add(indexes.getString("COLUMN_NAME"));
                                    if (!indexes.getBoolean("NON_UNIQUE"))
                                        uniqueColumns.add(indexes.getString("COLUMN_NAME"));
                                }
                            }
                            try (var columns =
                                    connection
                                            .getMetaData()
                                            .getColumns(
                                                    null,
                                                    null,
                                                    "PROJECT_API_KEY_ENTITY",
                                                    "KEY_HASH")) {
                                assertThat(columns.next()).isTrue();
                                assertThat(columns.getInt("COLUMN_SIZE")).isEqualTo(64);
                                assertThat(columns.getString("IS_NULLABLE")).isEqualTo("NO");
                            }
                        });
        assertThat(indexedColumns).contains("PROJECT_ID");
        assertThat(uniqueColumns).contains("KEY_HASH");
    }

    @Test
    void keyStateChangesUseUpdateAndPreserveSecretIdentity() {
        ProjectApiKey key =
                keys.save(
                        ProjectApiKey.create(
                                ProjectApiKeyId.of(91L),
                                ProjectId.of(1L),
                                UserId.of(1L),
                                "test",
                                "lfp_test",
                                "b".repeat(64),
                                ProjectApiKeyStatus.ACTIVE,
                                NOW,
                                null,
                                null));
        key.revoke(NOW.plusSeconds(10));
        keys.update(key);
        em.flush();
        em.clear();
        ProjectApiKey loaded = keys.findByKeyHash("b".repeat(64)).orElseThrow();
        assertThat(loaded.isRevoked()).isTrue();
        assertThat(loaded.getRevokedAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(loaded.getId()).isEqualTo(key.getId());
        assertThat(loaded.getProjectId()).isEqualTo(ProjectId.of(1L));
    }

    @Test
    void saveOfExistingDomainDoesNotSilentlyMergeChanges() {
        ProjectApiKey key =
                keys.save(
                        ProjectApiKey.create(
                                ProjectApiKeyId.of(91L),
                                ProjectId.of(1L),
                                UserId.of(1L),
                                "test",
                                "lfp_test",
                                "b".repeat(64),
                                ProjectApiKeyStatus.ACTIVE,
                                NOW,
                                null,
                                null));
        em.flush();
        em.clear();
        key.revoke(NOW.plusSeconds(10));
        assertThatThrownBy(
                        () -> {
                            keys.save(key);
                            em.flush();
                        })
                .isInstanceOf(RuntimeException.class);
    }

    private ProjectApiKeyEntity keyEntity(long id, String hash) {
        return ProjectApiKeyEntity.builder()
                .id(id)
                .projectId(1L)
                .createdBy(1L)
                .name("test")
                .keyPrefix("lfp_test")
                .keyHash(hash)
                .status(ProjectApiKeyStatus.ACTIVE)
                .createdAt(NOW)
                .build();
    }
}
