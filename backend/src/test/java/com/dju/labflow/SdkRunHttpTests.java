package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dju.labflow.auth.infrastructure.jwt.AccessTokenProvider;
import com.dju.labflow.project.key.application.IssueProjectApiKeyCommand;
import com.dju.labflow.project.key.application.ProjectApiKeyService;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 인증/서비스/저장소를 대체하지 않고 실제 Bearer 키로 SDK HTTP 계약을 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SdkRunHttpTests {
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
                            var runs =
                                    em.createQuery(
                                                    "select r from RunEntity r where r.createdBy in :ids",
                                                    RunEntity.class)
                                            .setParameter("ids", owners)
                                            .getResultList();
                            var ids = runs.stream().map(RunEntity::getId).toList();
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
        var response =
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
        var second =
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
        for (var request : validWrites()) {
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
        var request =
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
        var cookie =
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
        var key = keys.findByKeyHash(hasher.hash(client.rawKey())).orElseThrow();
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
        for (var request : validWrites()) {
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
        for (var request : validWrites()) {
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
        var result =
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

    private List<Write> validWrites() {
        return List.of(
                new Write("/parameters", "{\"parameters\":{\"optimizer\":\"adam\"}}"),
                new Write("/metrics", "{\"metrics\":{\"loss\":0.1}}"),
                new Write("/logs", "{\"message\":\"x\",\"level\":\"INFO\",\"source\":\"USER\"}"),
                new Write("/texts", "{\"key\":\"prediction\",\"text\":\"cat\"}"),
                new Write("/system-metrics", "{\"system\":{\"cpuUtilization\":10}}"),
                new Write("/complete", null),
                new Write("/fail", "{\"error\":\"failure\"}"));
    }

    private RunId create(Client client) throws Exception {
        var response =
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
        var request =
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
        var issued =
                issuance.issue(user.getId(), project.getId(), new IssueProjectApiKeyCommand("sdk"));
        return new Client(user.getId(), project.getId(), issued.rawApiKey());
    }

    private record Client(UserId ownerId, ProjectId projectId, String rawKey) {}

    private record Write(String suffix, String body) {}
}
