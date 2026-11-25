package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dju.labflow.auth.infrastructure.jwt.AccessTokenProvider;
import com.dju.labflow.project.key.application.IssueProjectApiKeyCommand;
import com.dju.labflow.project.key.application.IssueProjectApiKeyResult;
import com.dju.labflow.project.key.application.ProjectApiKeyService;
import com.dju.labflow.project.key.domain.ProjectApiKey;
import com.dju.labflow.project.key.domain.repository.ProjectApiKeyRepository;
import com.dju.labflow.project.key.util.ProjectApiKeyHasher;
import com.dju.labflow.project.project.domain.*;
import com.dju.labflow.project.project.domain.repository.ProjectRepository;
import com.dju.labflow.run.log.domain.RunLogSource;
import com.dju.labflow.run.log.domain.repository.RunLogRepository;
import com.dju.labflow.run.metric.domain.repository.RunMetricRepository;
import com.dju.labflow.run.parameter.domain.repository.RunParameterRepository;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.run.run.infrastructure.persistence.RunEntity;
import com.dju.labflow.run.systemmetric.domain.GpuMetric;
import com.dju.labflow.run.systemmetric.domain.repository.RunSystemMetricRepository;
import com.dju.labflow.run.systemmetric.infrastructure.persistence.RunSystemMetricEntity;
import com.dju.labflow.run.text.domain.repository.RunTextRepository;
import com.dju.labflow.sdk.presentation.RunCreateResponse;
import com.dju.labflow.user.domain.*;
import com.dju.labflow.user.domain.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 인증/서비스/저장소를 대체하지 않고 실제 Bearer 키로 SDK HTTP 계약을 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SdkRunHttpTests {
    @Test
    void recoversOfflineOutputAfterTerminationAndDeduplicatesLostAcknowledgements()
            throws Exception {
        Client client = client();
        RunId id = create(client);
        String captured = Instant.now().toString();
        String body =
                jsonMapper.writeValueAsString(
                        Map.of(
                                "message",
                                "offline stdout",
                                "level",
                                "INFO",
                                "source",
                                "STDOUT",
                                "recordedAt",
                                captured));
        identified(client, path(id) + "/logs", body, "ack-lost").andExpect(status().isNoContent());
        write(client, id, "/complete", null).andExpect(status().isNoContent());
        write(client, id, "/logs", body).andExpect(status().isConflict());
        for (String requestId : List.of("ack-lost", "offline-new", "offline-new")) {
            mvc.perform(
                            post(path(id) + "/logs")
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + client.rawKey())
                                    .header("X-Request-ID", requestId)
                                    .header("X-Labflow-Replay", "true")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isNoContent());
        }
        assertThat(logs.findByRunId(id, 100)).hasSize(2);
        mvc.perform(
                        post(path(id) + "/logs")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + client().rawKey())
                                .header("X-Request-ID", "other-project")
                                .header("X-Labflow-Replay", "true")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsRecoveryWithoutIdentityTimestampOrOutputSourceAndAfterTermination()
            throws Exception {
        Client client = client();
        RunId id = create(client);
        write(client, id, "/complete", null).andExpect(status().isNoContent());
        for (Map<String, String> payload :
                List.of(
                        Map.of("message", "missing timestamp", "level", "INFO", "source", "STDOUT"),
                        Map.of(
                                "message",
                                "not output",
                                "level",
                                "INFO",
                                "source",
                                "USER",
                                "recordedAt",
                                "2026-01-01T00:00:00Z"),
                        Map.of(
                                "message",
                                "too late",
                                "level",
                                "INFO",
                                "source",
                                "STDOUT",
                                "recordedAt",
                                "2099-01-01T00:00:00Z"))) {
            mvc.perform(
                            post(path(id) + "/logs")
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + client.rawKey())
                                    .header("X-Request-ID", "invalid")
                                    .header("X-Labflow-Replay", "true")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(jsonMapper.writeValueAsString(payload)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(
                        post(path(id) + "/logs")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + client.rawKey())
                                .header("X-Labflow-Replay", "true")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"message\":\"no identity\",\"level\":\"INFO\",\"source\":\"STDOUT\",\"recordedAt\":\"2026-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest());
        assertThat(logs.findByRunId(id, 100)).isEmpty();
    }

    @Test
    void textAndLogsRoundTripTheirFullUnicodeLimits() throws Exception {
        Client client = client();
        RunId id = create(client);
        String report = "분".repeat(65536);
        String message = "학".repeat(16000);
        write(
                        client,
                        id,
                        "/texts",
                        jsonMapper.writeValueAsString(Map.of("key", "report", "text", report)))
                .andExpect(status().isNoContent());
        write(
                        client,
                        id,
                        "/logs",
                        jsonMapper.writeValueAsString(
                                Map.of("message", message, "level", "INFO", "source", "USER")))
                .andExpect(status().isNoContent());
        assertThat(texts.findByRunId(id, 10).getFirst().getText()).isEqualTo(report);
        assertThat(logs.findByRunId(id, 10).getFirst().getMessage()).isEqualTo(message);
        write(
                        client,
                        id,
                        "/texts",
                        jsonMapper.writeValueAsString(
                                Map.of("key", "too-long", "text", report + "분")))
                .andExpect(status().isBadRequest());
    }

    private static final String BASE = "/api/v1/sdk/runs";
    private static final AtomicLong IDS = new AtomicLong(200000L);
    private final List<Long> owners = new ArrayList<>();
    @Autowired MockMvc mvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired ProjectApiKeyService issuance;
    @Autowired ProjectApiKeyRepository keys;
    @Autowired ProjectApiKeyHasher hasher;
    @Autowired AccessTokenProvider accessTokens;
    @Autowired RunParameterRepository parameters;
    @Autowired RunMetricRepository metrics;
    @Autowired RunLogRepository logs;
    @Autowired RunTextRepository texts;
    @Autowired RunSystemMetricRepository systemMetrics;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;

    // HTTP 서비스의 실제 커밋/롤백을 보기 위해 테스트 자체는 트랜잭션으로 감싸지 않는다.
    @AfterEach
    void cleanCommittedFixtures() {
        if (owners.isEmpty()) return;
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            List<RunEntity> runs =
                                    em.createQuery(
                                                    "select r from RunEntity r where r.createdBy in :ids",
                                                    RunEntity.class)
                                            .setParameter("ids", owners)
                                            .getResultList();
                            List<Long> ids = runs.stream().map(RunEntity::getId).toList();
                            em.createQuery(
                                            "delete from SdkRequestEntity r where r.projectId in "
                                                    + "(select p.id from ProjectEntity p where p.createdBy in :ids)")
                                    .setParameter("ids", owners)
                                    .executeUpdate();
                            if (!ids.isEmpty()) {
                                for (String type :
                                        List.of(
                                                "RunParameterEntity",
                                                "RunMetricEntity",
                                                "RunLogEntity",
                                                "RunTextEntity")) {
                                    em.createQuery(
                                                    "delete from "
                                                            + type
                                                            + " r where r.runId in :ids")
                                            .setParameter("ids", ids)
                                            .executeUpdate();
                                }
                                em.createQuery(
                                                "select r from RunSystemMetricEntity r where r.runId in :ids",
                                                RunSystemMetricEntity.class)
                                        .setParameter("ids", ids)
                                        .getResultList()
                                        .forEach(em::remove);
                                em.flush();
                                runs.forEach(em::remove);
                                em.flush();
                            }
                            em.createQuery(
                                            "delete from ProjectApiKeyEntity k where k.createdBy in :ids")
                                    .setParameter("ids", owners)
                                    .executeUpdate();
                            em.createQuery("delete from ProjectEntity p where p.createdBy in :ids")
                                    .setParameter("ids", owners)
                                    .executeUpdate();
                            em.createQuery("delete from UserEntity u where u.id in :ids")
                                    .setParameter("ids", owners)
                                    .executeUpdate();
                        });
    }

    @Test
    void bearerKeyCanCreateRecordReadAndCompleteWithoutCsrf() throws Exception {
        Client client = client();
        RunId id = create(client);
        send(client, HttpMethod.GET, path(id), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(client.projectId().value()))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.metadata.framework").value("pytorch"))
                .andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(jsonPath("$.createdBy").doesNotExist());
        write(
                        client,
                        id,
                        "/parameters",
                        "{\"parameters\":{\"optimizer\":\"adam\",\"batch_size\":\"32\"}}")
                .andExpect(status().isNoContent());
        write(
                        client,
                        id,
                        "/metrics",
                        """
                {"epoch":1,"step":10,"elapsedTime":1.5,"metrics":{"loss":0.125,"accuracy":0.94,"reward":-1},"recordedAt":"2026-11-01T00:00:00Z"}
                """)
                .andExpect(status().isNoContent());
        write(
                        client,
                        id,
                        "/logs",
                        "{\"message\":\"training started\",\"level\":\"INFO\",\"source\":\"USER\"}")
                .andExpect(status().isNoContent());
        write(client, id, "/logs", "{\"message\":\"\",\"level\":\"INFO\",\"source\":\"STDOUT\"}")
                .andExpect(status().isNoContent());
        write(
                        client,
                        id,
                        "/texts",
                        "{\"key\":\"prediction\",\"text\":\"고양이가 창가에 앉아 있다.\",\"step\":10}")
                .andExpect(status().isNoContent());
        write(
                        client,
                        id,
                        "/system-metrics",
                        """
                {"elapsedTime":1.5,"system":{"cpuUtilization":25,"ramUsed":1024,"ramTotal":8192,"gpus":[{"index":1,"utilization":20,"memoryUsed":1024,"memoryTotal":4096},{"index":0,"utilization":50,"memoryUsed":2048,"memoryTotal":4096}]}}
                """)
                .andExpect(status().isNoContent());
        write(client, id, "/complete", null)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        send(client, HttpMethod.GET, path(id), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.startedAt").isString())
                .andExpect(jsonPath("$.endedAt").isString());

        assertThat(parameters.findByRunId(id)).hasSize(2);
        assertThat(metrics.findByRunId(id, 10))
                .hasSize(3)
                .allSatisfy(
                        m -> {
                            assertThat(m.getStep()).isEqualTo(10L);
                            assertThat(m.getRecordedAt())
                                    .isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
                        });
        assertThat(logs.findByRunId(id, 10))
                .extracting(l -> l.getSource())
                .containsExactly(RunLogSource.USER, RunLogSource.STDOUT);
        assertThat(texts.findByRunId(id, 10))
                .singleElement()
                .satisfies(t -> assertThat(t.getText()).isEqualTo("고양이가 창가에 앉아 있다."));
        assertThat(systemMetrics.findByRunId(id, 10))
                .singleElement()
                .satisfies(
                        s -> {
                            assertThat(s.getGpus())
                                    .extracting(GpuMetric::index)
                                    .containsExactly(0, 1);
                            assertThat(s.getRamTotal()).isEqualTo(8192L);
                        });
        assertThat(keys.findByKeyHash(hasher.hash(client.rawKey())).orElseThrow().getLastUsedAt())
                .isNotNull();
    }

    @Test
    void emptyTextAndCpuOnlySnapshotAreSupported() throws Exception {
        Client client = client();
        RunId id = create(client);
        write(client, id, "/texts", "{\"key\":\"prediction\",\"text\":\"\"}")
                .andExpect(status().isNoContent());
        write(client, id, "/system-metrics", "{\"system\":{\"cpuUtilization\":10}}")
                .andExpect(status().isNoContent());
        assertThat(texts.findByRunId(id, 10))
                .singleElement()
                .satisfies(t -> assertThat(t.getText()).isEmpty());
        assertThat(systemMetrics.findByRunId(id, 10))
                .singleElement()
                .satisfies(s -> assertThat(s.getGpus()).isEmpty());
    }

    @Test
    void authenticatedKeyDeterminesProjectAndSameProjectKeysShareAccess() throws Exception {
        Client owner = client();
        Client other = client();
        MockHttpServletResponse response =
                mvc.perform(
                                post(BASE)
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                "Bearer " + owner.rawKey())
                                        .param("projectId", other.projectId().value().toString())
                                        .param("createdBy", other.ownerId().value().toString())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"name\":\"training\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse();
        RunId id =
                RunId.of(
                        jsonMapper
                                .readValue(response.getContentAsString(), RunCreateResponse.class)
                                .runId());
        IssueProjectApiKeyResult second =
                issuance.issue(
                        owner.ownerId(),
                        owner.projectId(),
                        new IssueProjectApiKeyCommand("second"));
        Client sameProject = new Client(owner.ownerId(), owner.projectId(), second.rawApiKey());
        send(sameProject, HttpMethod.GET, path(id), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(owner.projectId().value()));
    }

    @Test
    void otherProjectKeyCannotReadWriteOrEndPublicProjectRun() throws Exception {
        Client owner = client();
        RunId id = create(owner);
        Client other = client();
        send(other, HttpMethod.GET, path(id), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        for (Write request : validWrites()) {
            write(other, id, request.suffix(), request.body())
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        assertThat(metrics.findByRunId(id, 10)).isEmpty();
        assertThat(logs.findByRunId(id, 10)).isEmpty();
        send(owner, HttpMethod.GET, path(id), null)
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Bearer ", "Basic abc", "Bearer invalid-key"})
    void missingOrInvalidBearerKeyIs401(String authorization) throws Exception {
        MockHttpServletRequestBuilder request =
                post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"training\"}");
        if (authorization != null) request.header(HttpHeaders.AUTHORIZATION, authorization);
        mvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void jwtCookieCannotReplaceSdkKeyAndDoesNotInterfereWithValidKey() throws Exception {
        Client client = client();
        Cookie cookie =
                new Cookie(
                        "access_token",
                        accessTokens.createAccessToken(client.ownerId(), Role.USER));
        mvc.perform(
                        post(BASE)
                                .cookie(cookie)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"training\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post(BASE)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + client.rawKey())
                                .cookie(new Cookie("access_token", "invalid-jwt"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"training\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void sdkKeyDoesNotAuthorizeBrowserKeyIssuance() throws Exception {
        Client client = client();
        Cookie csrf =
                mvc.perform(get("/api/v1/auth/csrf"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getCookie("XSRF-TOKEN");
        assertThat(csrf).isNotNull();
        mvc.perform(
                        post("/api/v1/project-api-keys/issue/" + client.projectId().value())
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + client.rawKey())
                                .cookie(csrf)
                                .header("X-XSRF-TOKEN", csrf.getValue())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"unauthorized\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokedKeyCannotReadOrWriteExistingRun() throws Exception {
        Client client = client();
        RunId id = create(client);
        ProjectApiKey key = keys.findByKeyHash(hasher.hash(client.rawKey())).orElseThrow();
        key.revoke(Instant.now());
        keys.update(key);
        send(client, HttpMethod.GET, path(id), null).andExpect(status().isUnauthorized());
        write(client, id, "/metrics", "{\"metrics\":{\"loss\":0.1}}")
                .andExpect(status().isUnauthorized());
        assertThat(metrics.findByRunId(id, 10)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = AccountStatus.class,
            names = {"BLOCKED", "PENDING_VERIFICATION"})
    void inactiveOwnerCannotUsePreviouslyIssuedKey(AccountStatus state) throws Exception {
        Client client = client();
        RunId id = create(client);
        User user = users.findById(client.ownerId()).orElseThrow();
        users.update(
                User.restore(
                        user.getId(),
                        user.getEmail(),
                        user.getEncodedPassword(),
                        user.getName(),
                        user.getRole(),
                        state));
        send(client, HttpMethod.GET, path(id), null).andExpect(status().isUnauthorized());
        write(client, id, "/complete", null).andExpect(status().isUnauthorized());
    }

    @Test
    void completionRetriesPreserveTimeAndEndedRunRejectsAllNewRecords() throws Exception {
        Client client = client();
        RunId id = create(client);
        write(client, id, "/complete", null).andExpect(status().isNoContent());
        String completed =
                send(client, HttpMethod.GET, path(id), null)
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        write(client, id, "/complete", null).andExpect(status().isNoContent());
        send(client, HttpMethod.GET, path(id), null).andExpect(content().json(completed));
        for (Write request : validWrites()) {
            if (request.suffix().equals("/complete")) continue;
            write(client, id, request.suffix(), request.body())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("RUN_STATE_CONFLICT"));
        }
        assertThat(parameters.findByRunId(id)).isEmpty();
        assertThat(metrics.findByRunId(id, 10)).isEmpty();
        assertThat(logs.findByRunId(id, 10)).isEmpty();
        assertThat(texts.findByRunId(id, 10)).isEmpty();
        assertThat(systemMetrics.findByRunId(id, 10)).isEmpty();
    }

    @Test
    void failureRetryIsIdempotentAndConflictingResultIs409() throws Exception {
        Client client = client();
        RunId id = create(client);
        String failure = "{\"error\":\"out of memory\"}";
        write(client, id, "/fail", failure).andExpect(status().isNoContent());
        write(client, id, "/fail", failure).andExpect(status().isNoContent());
        send(client, HttpMethod.GET, path(id), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error").value("out of memory"));
        write(client, id, "/fail", "{\"error\":\"another error\"}")
                .andExpect(status().isConflict());
        write(client, id, "/complete", null).andExpect(status().isConflict());
    }

    @Test
    void parameterRetryIsIdempotentAndConflictingBatchRollsBack() throws Exception {
        Client client = client();
        RunId id = create(client);
        String body = "{\"parameters\":{\"optimizer\":\"adam\"}}";
        write(client, id, "/parameters", body).andExpect(status().isNoContent());
        write(client, id, "/parameters", body).andExpect(status().isNoContent());
        write(
                        client,
                        id,
                        "/parameters",
                        "{\"parameters\":{\"new_parameter\":\"v\",\"optimizer\":\"sgd\"}}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUN_STATE_CONFLICT"));
        assertThat(parameters.findByRunId(id))
                .singleElement()
                .satisfies(
                        p -> {
                            assertThat(p.getKey()).isEqualTo("optimizer");
                            assertThat(p.getValue()).isEqualTo("adam");
                        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void invalidRunIdIs400(String id) throws Exception {
        Client client = client();
        send(client, HttpMethod.GET, BASE + "/" + id, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        send(client, HttpMethod.POST, BASE + "/" + id + "/metrics", "{\"metrics\":{\"loss\":0.1}}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingRunIs404ForReadAndEveryWrite() throws Exception {
        Client client = client();
        RunId missing = RunId.of(1L);
        send(client, HttpMethod.GET, path(missing), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
        for (Write request : validWrites()) {
            write(client, missing, request.suffix(), request.body())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"name\":\" \"}",
                "null",
                "{",
                "{\"name\":\"test\",\"metadata\":{\"framework\":null}}"
            })
    void invalidCreateBodyIs400(String body) throws Exception {
        send(client(), HttpMethod.POST, BASE, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @ParameterizedTest
    @MethodSource("invalidRecordBodies")
    void invalidRecordBodyIs400WithoutStoredRecords(String suffix, String body) throws Exception {
        Client client = client();
        RunId id = create(client);
        write(client, id, suffix, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        assertThat(parameters.findByRunId(id)).isEmpty();
        assertThat(metrics.findByRunId(id, 10)).isEmpty();
        assertThat(logs.findByRunId(id, 10)).isEmpty();
        assertThat(texts.findByRunId(id, 10)).isEmpty();
        assertThat(systemMetrics.findByRunId(id, 10)).isEmpty();
    }

    static Stream<Arguments> invalidRecordBodies() {
        return Stream.of(
                Arguments.of("/parameters", "{\"parameters\":{}}"),
                Arguments.of("/parameters", "{\"parameters\":{\"optimizer\":null}}"),
                Arguments.of("/parameters", "{\"parameters\":{\" \":\"v\"}}"),
                Arguments.of("/metrics", "{\"metrics\":{}}"),
                Arguments.of("/metrics", "{\"metrics\":{\"loss\":\"0.125\"}}"),
                Arguments.of("/metrics", "{\"metrics\":{\"loss\":true}}"),
                Arguments.of("/metrics", "{\"metrics\":{\"loss\":null}}"),
                Arguments.of("/metrics", "{\"metrics\":{\"loss\":{\"value\":1}}}"),
                Arguments.of("/metrics", "{\"metrics\":{\"loss\":[1]}}"),
                Arguments.of("/metrics", "{\"metrics\":{\"valid\":1,\"invalid\":1e309}}"),
                Arguments.of("/metrics", "{\"step\":-1,\"metrics\":{\"loss\":1}}"),
                Arguments.of("/metrics", "{\"epoch\":-1,\"metrics\":{\"loss\":1}}"),
                Arguments.of("/metrics", "{\"elapsedTime\":-1,\"metrics\":{\"loss\":1}}"),
                Arguments.of(
                        "/metrics", "{\"recordedAt\":\"not-a-date\",\"metrics\":{\"loss\":1}}"),
                Arguments.of("/logs", "{\"level\":\"INFO\",\"source\":\"USER\"}"),
                Arguments.of(
                        "/logs", "{\"message\":\"x\",\"level\":\"INVALID\",\"source\":\"USER\"}"),
                Arguments.of(
                        "/logs", "{\"message\":\"x\",\"level\":\"INFO\",\"source\":\"INVALID\"}"),
                Arguments.of("/texts", "{\"key\":\"prediction\",\"text\":null}"),
                Arguments.of("/texts", "{\"key\":\"prediction\",\"text\":\"x\",\"step\":-1}"),
                Arguments.of(
                        "/texts",
                        "{\"key\":\"prediction\",\"text\":\"" + "x".repeat(65537) + "\"}"),
                Arguments.of("/system-metrics", "{\"system\":null}"),
                Arguments.of("/system-metrics", "{\"system\":{}}"),
                Arguments.of("/system-metrics", "{\"system\":{\"cpuUtilization\":101}}"),
                Arguments.of("/system-metrics", "{\"system\":{\"ramUsed\":20,\"ramTotal\":10}}"),
                Arguments.of("/system-metrics", "{\"system\":{\"gpus\":[null]}}"),
                Arguments.of("/system-metrics", "{\"system\":{\"gpus\":[{\"utilization\":10}]}}"),
                Arguments.of(
                        "/system-metrics",
                        "{\"system\":{\"gpus\":[{\"index\":0,\"utilization\":101}]}}"),
                Arguments.of(
                        "/system-metrics", "{\"system\":{\"gpus\":[{\"index\":0},{\"index\":0}]}}"),
                Arguments.of("/fail", "{\"error\":\" \"}"));
    }

    @Test
    void openApiDocumentsBearerOnlySecurityAndActualDtoContract() throws Exception {
        ResultActions result =
                mvc.perform(get("/v3/api-docs"))
                        .andExpect(status().isOk())
                        .andExpect(
                                jsonPath("$.paths['" + BASE + "'].post.responses['201']").exists())
                        .andExpect(
                                jsonPath("$.paths['" + BASE + "/{runId}'].get.parameters.length()")
                                        .value(1))
                        .andExpect(
                                jsonPath(
                                                "$.components.schemas.MetricRequest.properties.metrics.additionalProperties.type")
                                        .value("number"))
                        .andExpect(
                                jsonPath(
                                                "$.components.schemas.LogCreateRequest.properties.message.maxLength")
                                        .value(16000))
                        .andExpect(
                                jsonPath(
                                                "$.components.schemas.TextCreateRequest.properties.text.maxLength")
                                        .value(65536));
        for (String verb : List.of("post", "get", "put")) {
            for (String route :
                    verb.equals("get")
                            ? List.of(BASE + "/{runId}")
                            : verb.equals("put")
                                    ? List.of(BASE + "/{runId}/parameters")
                                    : List.of(
                                            BASE,
                                            BASE + "/{runId}/metrics",
                                            BASE + "/{runId}/logs",
                                            BASE + "/{runId}/texts",
                                            BASE + "/{runId}/system-metrics",
                                            BASE + "/{runId}/heartbeat",
                                            BASE + "/{runId}/complete",
                                            BASE + "/{runId}/fail")) {
                String operation = "$.paths['" + route + "']." + verb;
                result.andExpect(jsonPath(operation + ".security.length()").value(1))
                        .andExpect(jsonPath(operation + ".security[0].projectApiKey").exists())
                        .andExpect(jsonPath(operation + ".security[0].cookieAuth").doesNotExist())
                        .andExpect(jsonPath(operation + ".security[0].csrfToken").doesNotExist())
                        .andExpect(jsonPath(operation + ".responses['401']").exists())
                        .andExpect(jsonPath(operation + ".responses['409']").exists());
            }
        }
    }

    @Test
    void contextPathIsPreservedInCreatedLocation() throws Exception {
        Client client = client();
        mvc.perform(
                        post("/labflow" + BASE)
                                .contextPath("/labflow")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + client.rawKey())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"training\"}"))
                .andExpect(status().isCreated())
                .andExpect(
                        header().string(
                                        HttpHeaders.LOCATION,
                                        org.hamcrest.Matchers.startsWith(
                                                "http://localhost/labflow" + BASE + "/")));
    }

    @Test
    void duplicateCreateReturnsOriginalResultAndCanonicalizesMetadataOrder() throws Exception {
        Client client = client();
        String firstBody = "{\"name\":\"dedupe\",\"metadata\":{\"b\":\"2\",\"a\":\"1\"}}";
        MockHttpServletResponse first =
                identified(client, BASE, firstBody, "create-1")
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse();
        identified(
                        client,
                        BASE,
                        "{\"metadata\":{\"a\":\"1\",\"b\":\"2\"},\"name\":\"dedupe\"}",
                        "create-1")
                .andExpect(status().isCreated())
                .andExpect(content().json(first.getContentAsString()))
                .andExpect(
                        header().string(
                                        HttpHeaders.LOCATION,
                                        first.getHeader(HttpHeaders.LOCATION)));
        identified(client, BASE, "{\"name\":\"different\"}", "create-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        IssueProjectApiKeyResult secondKey =
                issuance.issue(
                        client.ownerId(),
                        client.projectId(),
                        new IssueProjectApiKeyCommand("rotated"));
        identified(
                        new Client(client.ownerId(), client.projectId(), secondKey.rawApiKey()),
                        BASE,
                        firstBody,
                        "create-1")
                .andExpect(status().isCreated())
                .andExpect(content().json(first.getContentAsString()));
        Client other = client();
        identified(other, BASE, firstBody, "create-1").andExpect(status().isCreated());
    }

    @Test
    void appendRecordsAreWrittenOnceAndReplayWorksAfterCompletion() throws Exception {
        Client client = client();
        RunId id = create(client);
        List<Write> records =
                validWrites().stream()
                        .filter(write -> !Set.of("/complete", "/fail").contains(write.suffix()))
                        .toList();
        for (Write write : records) {
            String requestId = "record-" + write.suffix().substring(1);
            identified(client, path(id) + write.suffix(), write.body(), requestId)
                    .andExpect(status().isNoContent());
            identified(client, path(id) + write.suffix(), write.body(), requestId)
                    .andExpect(status().isNoContent());
        }
        assertThat(parameters.findByRunId(id)).hasSize(1);
        assertThat(metrics.findByRunId(id, 100)).hasSize(1);
        assertThat(logs.findByRunId(id, 100)).hasSize(1);
        assertThat(texts.findByRunId(id, 100)).hasSize(1);
        assertThat(systemMetrics.findByRunId(id, 100)).hasSize(1);
        String heartbeat =
                mvc.perform(
                                get(path(id))
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                "Bearer " + client.rawKey()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        identified(client, path(id) + "/complete", null, "finish-1")
                .andExpect(status().isNoContent());
        identified(client, path(id) + "/complete", null, "finish-1")
                .andExpect(status().isNoContent());
        for (Write write : records) {
            identified(
                            client,
                            path(id) + write.suffix(),
                            write.body(),
                            "record-" + write.suffix().substring(1))
                    .andExpect(status().isNoContent());
        }
        mvc.perform(get(path(id)).header(HttpHeaders.AUTHORIZATION, "Bearer " + client.rawKey()))
                .andExpect(
                        jsonPath("$.lastHeartbeatAt")
                                .value(
                                        jsonMapper
                                                .readTree(heartbeat)
                                                .get("lastHeartbeatAt")
                                                .asString()));
        identified(
                        client,
                        path(id) + "/logs",
                        records.stream()
                                .filter(w -> w.suffix().equals("/logs"))
                                .findFirst()
                                .orElseThrow()
                                .body(),
                        "new-after-finish")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUN_STATE_CONFLICT"));
        assertThat(logs.findByRunId(id, 100)).hasSize(1);
    }

    @Test
    void failedWriteRollsBackRecordsAndDoesNotConsumeRequestId() throws Exception {
        Client client = client();
        RunId id = create(client);
        write(client, id, "/parameters", "{\"parameters\":{\"optimizer\":\"adam\"}}")
                .andExpect(status().isNoContent());
        identified(
                        client,
                        path(id) + "/parameters",
                        "{\"parameters\":{\"new\":\"value\",\"optimizer\":\"sgd\"}}",
                        "rollback-1")
                .andExpect(status().isConflict());
        assertThat(parameters.findByRunId(id)).hasSize(1);
        identified(
                        client,
                        path(id) + "/parameters",
                        "{\"parameters\":{\"new\":\"value\",\"optimizer\":\"adam\"}}",
                        "rollback-1")
                .andExpect(status().isNoContent());
        assertThat(parameters.findByRunId(id)).hasSize(2);
    }

    @Test
    void requestIdentityIncludesRunOperationAndPayloadAndRemainsCaseSensitive() throws Exception {
        Client client = client();
        RunId first = create(client);
        RunId second = create(client);
        String body = "{\"message\":\"one\",\"source\":\"USER\",\"level\":\"INFO\"}";
        identified(client, path(first) + "/logs", body, "Event-A")
                .andExpect(status().isNoContent());
        identified(client, path(first) + "/logs", body, "event-a")
                .andExpect(status().isNoContent());
        identified(client, path(first) + "/logs", body.replace("one", "two"), "Event-A")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        identified(client, path(second) + "/logs", body, "Event-A")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        identified(client, path(first) + "/heartbeat", null, "Event-A")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        Client other = client();
        identified(other, path(first) + "/logs", body, "Event-A").andExpect(status().isForbidden());
        assertThat(logs.findByRunId(first, 100)).hasSize(2);
        assertThat(logs.findByRunId(second, 100)).isEmpty();
        for (String invalid : List.of("", " ", "a".repeat(65), "bad/id", "한글")) {
            identified(client, path(first) + "/logs", body, invalid)
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void concurrentDuplicatesCreateOneRunAndOneLog() throws Exception {
        Client client = client();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Long>> creates = new ArrayList<>();
            for (int i = 0; i < 8; i++)
                creates.add(
                        () ->
                                jsonMapper
                                        .readValue(
                                                identified(
                                                                client,
                                                                BASE,
                                                                "{\"name\":\"concurrent\"}",
                                                                "concurrent-create")
                                                        .andExpect(status().isCreated())
                                                        .andReturn()
                                                        .getResponse()
                                                        .getContentAsString(),
                                                RunCreateResponse.class)
                                        .runId());
            List<Future<Long>> results = executor.invokeAll(creates, 15, TimeUnit.SECONDS);
            Set<Long> ids = new HashSet<>();
            for (Future<Long> result : results) ids.add(result.get());
            assertThat(ids).hasSize(1);
            Long id = ids.iterator().next();
            List<Callable<Void>> writes = new ArrayList<>();
            for (int i = 0; i < 8; i++)
                writes.add(
                        () -> {
                            identified(
                                            client,
                                            BASE + "/" + id + "/logs",
                                            "{\"message\":\"one\",\"source\":\"USER\",\"level\":\"INFO\"}",
                                            "concurrent-log")
                                    .andExpect(status().isNoContent());
                            return null;
                        });
            for (Future<Void> result : executor.invokeAll(writes, 15, TimeUnit.SECONDS))
                result.get();
            assertThat(logs.findByRunId(RunId.of(id), 100)).hasSize(1);
            Long count =
                    new TransactionTemplate(transactions)
                            .execute(
                                    status ->
                                            em.createQuery(
                                                            "select count(r) from RunEntity r where r.projectId=:id",
                                                            Long.class)
                                                    .setParameter("id", client.projectId().value())
                                                    .getSingleResult());
            assertThat(count).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    private ResultActions identified(Client client, String path, String body, String requestId)
            throws Exception {
        MockHttpServletRequestBuilder request =
                request(path.endsWith("/parameters") ? HttpMethod.PUT : HttpMethod.POST, path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + client.rawKey())
                        .header("X-Request-ID", requestId);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request);
    }

    private List<Write> validWrites() {
        return List.of(
                new Write("/parameters", "{\"parameters\":{\"optimizer\":\"adam\"}}"),
                new Write("/metrics", "{\"metrics\":{\"loss\":0.1}}"),
                new Write("/logs", "{\"message\":\"x\",\"level\":\"INFO\",\"source\":\"USER\"}"),
                new Write("/texts", "{\"key\":\"prediction\",\"text\":\"cat\"}"),
                new Write("/system-metrics", "{\"system\":{\"cpuUtilization\":10}}"),
                new Write("/heartbeat", null),
                new Write("/complete", null),
                new Write("/fail", "{\"error\":\"failure\"}"));
    }

    private RunId create(Client client) throws Exception {
        MockHttpServletResponse response =
                send(
                                client,
                                HttpMethod.POST,
                                BASE,
                                "{\"name\":\"training\",\"metadata\":{\"framework\":\"pytorch\"}}")
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse();
        Long id =
                jsonMapper
                        .readValue(response.getContentAsString(), RunCreateResponse.class)
                        .runId();
        assertThat(id).isPositive();
        assertThat(response.getHeader(HttpHeaders.LOCATION))
                .isEqualTo("http://localhost" + BASE + "/" + id);
        return RunId.of(id);
    }

    private ResultActions write(Client client, RunId id, String suffix, String body)
            throws Exception {
        return send(
                client,
                suffix.equals("/parameters") ? HttpMethod.PUT : HttpMethod.POST,
                path(id) + suffix,
                body);
    }

    private ResultActions send(Client client, HttpMethod method, String path, String body)
            throws Exception {
        MockHttpServletRequestBuilder request =
                request(method, path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + client.rawKey());
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request);
    }

    private String path(RunId id) {
        return BASE + "/" + id.value();
    }

    private Client client() {
        long id = IDS.incrementAndGet();
        owners.add(id);
        User user =
                users.save(
                        User.restore(
                                UserId.of(id),
                                "sdk" + id + "@example.test",
                                "encoded",
                                "sdk test",
                                Role.USER,
                                AccountStatus.ACTIVE));
        Project project =
                projects.save(
                        Project.create(
                                ProjectId.of(id),
                                "project",
                                null,
                                user.getId(),
                                ProjectVisibility.PUBLIC,
                                Instant.now()));
        IssueProjectApiKeyResult issued =
                issuance.issue(user.getId(), project.getId(), new IssueProjectApiKeyCommand("sdk"));
        return new Client(user.getId(), project.getId(), issued.rawApiKey());
    }

    private record Client(UserId ownerId, ProjectId projectId, String rawKey) {}

    private record Write(String suffix, String body) {}
}
