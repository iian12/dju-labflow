package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dju.labflow.auth.infrastructure.jwt.AccessTokenProvider;
import com.dju.labflow.global.id.IdGenerator;
import com.dju.labflow.project.key.application.*;
import com.dju.labflow.project.key.domain.ProjectApiKeyPrincipal;
import com.dju.labflow.project.project.domain.*;
import com.dju.labflow.project.project.domain.repository.*;
import com.dju.labflow.run.log.application.RunLogCommand;
import com.dju.labflow.run.log.domain.*;
import com.dju.labflow.run.metric.application.RunMetricCommand;
import com.dju.labflow.run.parameter.application.RunParameterCommand;
import com.dju.labflow.run.run.application.*;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.run.systemmetric.application.RunSystemMetricCommand;
import com.dju.labflow.run.text.application.RunTextCommand;
import com.dju.labflow.user.domain.*;
import com.dju.labflow.user.domain.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** JWT/공개 범위/프로젝트 소속/페이지/기록 원문을 실제 HTTP와 H2에서 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunQueryHttpTests {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired IdGenerator ids;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired ProjectContentsRepository contents;
    @Autowired ProjectApiKeyService issuance;
    @Autowired RunService runs;
    @Autowired RunRecordService records;
    @Autowired AccessTokenProvider tokens;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    final List<UserId> owners = new ArrayList<>();

    @Test
    void disconnectedRunStaysRunningAndHeartbeatReconnectsTheSameRun() throws Exception {
        var f = fixture(ProjectVisibility.PUBLIC);
        records.metrics(
                f.principal(),
                f.run(),
                new RunMetricCommand(1, 1L, 1.0, Map.of("loss", 0.25), null));
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status ->
                                em.createQuery(
                                                "update RunEntity r set r.startedAt = :at where r.id = :id")
                                        .setParameter("at", Instant.now().minusSeconds(300))
                                        .setParameter("id", f.run().value())
                                        .executeUpdate());
        String detail = base(f) + "/" + f.run().value();
        mvc.perform(get(detail))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectionStatus").value("DISCONNECTED"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.endedAt").isEmpty())
                .andExpect(jsonPath("$.error").isEmpty());
        mvc.perform(get(base(f)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].connectionStatus").value("DISCONNECTED"))
                .andExpect(jsonPath("$.counts.RUNNING").value(1));
        mvc.perform(
                        get("/api/v1/sdk/runs/" + f.run().value())
                                .header("Authorization", "Bearer " + f.key()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectionStatus").value("DISCONNECTED"));
        mvc.perform(
                        post("/api/v1/sdk/runs/" + f.run().value() + "/heartbeat")
                                .header("Authorization", "Bearer " + f.key()))
                .andExpect(status().isNoContent());
        mvc.perform(get(detail))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectionStatus").value("CONNECTED"))
                .andExpect(jsonPath("$.status").value("RUNNING"));
        records.metrics(
                f.principal(),
                f.run(),
                new RunMetricCommand(2, 2L, 2.0, Map.of("loss", 0.1), null));
        mvc.perform(get(detail + "/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.length()").value(2));
    }

    @Test
    void ownerCanManuallyStopRunWithCsrfAndRetryWithoutChangingEndTime() throws Exception {
        var f = fixture(ProjectVisibility.PRIVATE);
        String detail = base(f) + "/" + f.run().value();
        var csrf =
                mvc.perform(get("/api/v1/auth/csrf"))
                        .andReturn()
                        .getResponse()
                        .getCookie("XSRF-TOKEN");
        mvc.perform(
                        post(detail + "/stop")
                                .cookie(cookie(f.owner()), csrf)
                                .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNoContent());
        var ended = runs.get(f.principal(), f.run()).getEndedAt();
        mvc.perform(
                        post(detail + "/stop")
                                .cookie(cookie(f.owner()), csrf)
                                .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNoContent());
        assertThat(runs.get(f.principal(), f.run()).getEndedAt()).isEqualTo(ended);
        mvc.perform(get(detail).cookie(cookie(f.owner())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error").value(RunManagementService.STOP_REASON))
                .andExpect(jsonPath("$.connectionStatus").value("INACTIVE"));
        mvc.perform(
                        post("/api/v1/sdk/runs/" + f.run().value() + "/heartbeat")
                                .header("Authorization", "Bearer " + f.key()))
                .andExpect(status().isConflict());
    }

    @Test
    void manualStopRequiresLoginCsrfAndOwnerEvenForPublicProjects() throws Exception {
        var f = fixture(ProjectVisibility.PUBLIC);
        var other = user();
        String stop = base(f) + "/" + f.run().value() + "/stop";
        var csrf =
                mvc.perform(get("/api/v1/auth/csrf"))
                        .andReturn()
                        .getResponse()
                        .getCookie("XSRF-TOKEN");
        mvc.perform(post(stop).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(stop).cookie(cookie(f.owner()))).andExpect(status().isForbidden());
        mvc.perform(post(stop).cookie(cookie(other), csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post(stop)
                                .header("Authorization", "Bearer " + f.key())
                                .cookie(csrf)
                                .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isUnauthorized());
        assertThat(runs.get(f.principal(), f.run()).getStatus().name()).isEqualTo("RUNNING");
    }

    @Test
    void manualStopRejectsForeignRunsAndNeverOverwritesCompletionOrFailure() throws Exception {
        var first = fixture(ProjectVisibility.PUBLIC);
        var second = fixture(ProjectVisibility.PUBLIC);
        var csrf =
                mvc.perform(get("/api/v1/auth/csrf"))
                        .andReturn()
                        .getResponse()
                        .getCookie("XSRF-TOKEN");
        mvc.perform(
                        post(base(first) + "/" + second.run().value() + "/stop")
                                .cookie(cookie(first.owner()), csrf)
                                .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNotFound());
        runs.complete(first.principal(), first.run());
        mvc.perform(
                        post(base(first) + "/" + first.run().value() + "/stop")
                                .cookie(cookie(first.owner()), csrf)
                                .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isConflict());
        runs.fail(second.principal(), second.run(), new RunFailCommand("training error"));
        mvc.perform(
                        post(base(second) + "/" + second.run().value() + "/stop")
                                .cookie(cookie(second.owner()), csrf)
                                .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isConflict());
        assertThat(runs.get(second.principal(), second.run()).getError())
                .isEqualTo("training error");
    }

    @AfterEach
    void cleanup() {
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            for (UserId id : owners) {
                                for (var project : projects.findByCreatedBy(id, 0, 100).items()) {
                                    var locked =
                                            projects.findByIdForUpdate(project.getId())
                                                    .orElseThrow();
                                    contents.deleteByProjectId(project.getId());
                                    projects.delete(locked);
                                }
                                em.createQuery("delete from UserEntity u where u.id = :id")
                                        .setParameter("id", id.value())
                                        .executeUpdate();
                            }
                        });
    }

    @Test
    void ownerReadsActualRecordsAndProjectWideCounts() throws Exception {
        var f = fixture(ProjectVisibility.PRIVATE);
        records.parameters(
                f.principal(), f.run(), new RunParameterCommand(Map.of("batch_size", "64")));
        records.metrics(
                f.principal(),
                f.run(),
                new RunMetricCommand(1, 18L, 1.5, Map.of("loss", 0.25, "accuracy", 0.9), null));
        records.log(
                f.principal(),
                f.run(),
                new RunLogCommand("학습 시작\n", RunLogLevel.INFO, RunLogSource.USER, null));
        records.text(f.principal(), f.run(), new RunTextCommand("prediction", "고양이 🐈", 18L, null));
        records.systemMetric(
                f.principal(),
                f.run(),
                new RunSystemMetricCommand(25.0, 1024L, 2048L, List.of(), 1.5, null));
        runs.complete(f.principal(), f.run());
        runs.create(f.principal(), new RunCreateCommand("second", Map.of()));
        mvc.perform(get(base(f) + "?size=1").cookie(cookie(f.owner())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.counts.RUNNING").value(1))
                .andExpect(jsonPath("$.counts.COMPLETED").value(1));
        mvc.perform(get(base(f) + "/" + f.run().value()).cookie(cookie(f.owner())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.metadata.framework").value("test"));
        mvc.perform(get(base(f) + "/" + f.run().value() + "/records").cookie(cookie(f.owner())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parameters[0].value").value("64"))
                .andExpect(jsonPath("$.metrics.length()").value(2))
                .andExpect(jsonPath("$.metrics[0].step").value(18))
                .andExpect(jsonPath("$.logs[0].message").value("학습 시작\n"))
                .andExpect(jsonPath("$.texts[0].text").value("고양이 🐈"))
                .andExpect(jsonPath("$.systemMetrics[0].ramUsed").value(1024));
        mvc.perform(
                        get(base(f) + "/" + f.run().value() + "/records?limit=1")
                                .cookie(cookie(f.owner())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.length()").value(1));
    }

    @Test
    void privateRunRoutesRequireTheOwnerAndDoNotAcceptSdkKeysInstead() throws Exception {
        var f = fixture(ProjectVisibility.PRIVATE);
        var other = user();
        for (String suffix :
                List.of(
                        "",
                        "/" + f.run().value(),
                        "/" + f.run().value() + "/records",
                        "/" + f.run().value() + "/records/logs")) {
            mvc.perform(get(base(f) + suffix)).andExpect(status().isUnauthorized());
            mvc.perform(get(base(f) + suffix).cookie(cookie(other)))
                    .andExpect(status().isForbidden());
            mvc.perform(get(base(f) + suffix).header("Authorization", "Bearer " + f.key()))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void publicRunRoutesAreReadableWithoutAKeyOrLogin() throws Exception {
        var f = fixture(ProjectVisibility.PUBLIC);
        for (String suffix :
                List.of(
                        "",
                        "/" + f.run().value(),
                        "/" + f.run().value() + "/records",
                        "/" + f.run().value() + "/records/logs"))
            mvc.perform(get(base(f) + suffix)).andExpect(status().isOk());
    }

    @Test
    void runIdMustBelongToTheProjectInTheUrl() throws Exception {
        var first = fixture(ProjectVisibility.PUBLIC);
        var second = fixture(ProjectVisibility.PUBLIC);
        mvc.perform(get(base(first) + "/" + second.run().value())).andExpect(status().isNotFound());
        mvc.perform(get(base(first) + "/" + second.run().value() + "/records"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidPageAndRecordLimitsAreRejected() throws Exception {
        var f = fixture(ProjectVisibility.PUBLIC);
        for (String query : List.of("?page=-1", "?size=0", "?size=101"))
            mvc.perform(get(base(f) + query)).andExpect(status().isBadRequest());
        for (String query : List.of("?limit=0", "?limit=1001"))
            mvc.perform(get(base(f) + "/" + f.run().value() + "/records" + query))
                    .andExpect(status().isBadRequest());
    }

    @Test
    void cursorPagesTraverseEveryRecordKindWithoutDuplicatesAtEqualTimestamps() throws Exception {
        var f = fixture(ProjectVisibility.PUBLIC);
        Instant when = Instant.parse("2026-11-19T00:00:00.123456Z");
        for (int i = 0; i < 7; i++) {
            records.metrics(
                    f.principal(),
                    f.run(),
                    new RunMetricCommand(
                            1, (long) i, (double) i, Map.of("loss", (double) i), when));
            records.log(
                    f.principal(),
                    f.run(),
                    new RunLogCommand("log-" + i, RunLogLevel.INFO, RunLogSource.USER, when));
            records.text(
                    f.principal(),
                    f.run(),
                    new RunTextCommand("sample", "text-" + i, (long) i, when));
            records.systemMetric(
                    f.principal(),
                    f.run(),
                    new RunSystemMetricCommand((double) i, 10L, 20L, List.of(), (double) i, when));
        }
        for (String kind : List.of("metrics", "logs", "texts", "system-metrics")) {
            List<Long> ascending = new ArrayList<>();
            String query = "?limit=2";
            for (int page = 0; page < 4; page++) {
                var result = page(f, kind, query);
                ascending.addAll(itemIds(result));
                assertThat(result.get("items").size()).isEqualTo(page == 3 ? 1 : 2);
                assertThat(result.get("hasMore").asBoolean()).isEqualTo(page < 3);
                if (page < 3) query = "?limit=2&after=" + result.get("nextCursor").asString();
                else assertThat(result.get("nextCursor").isNull()).isTrue();
            }
            assertThat(ascending).hasSize(7).doesNotHaveDuplicates().isSorted();
            var latest = page(f, kind, "?limit=2&latest=true");
            assertThat(itemIds(latest)).isEqualTo(ascending.subList(5, 7));
            List<Long> backwards = new ArrayList<>(itemIds(latest));
            for (int i = 0; i < 3; i++) {
                latest = page(f, kind, "?limit=2&before=" + latest.get("beforeCursor").asString());
                backwards.addAll(0, itemIds(latest));
            }
            assertThat(backwards).isEqualTo(ascending);
            assertThat(latest.get("hasMore").asBoolean()).isFalse();
            var empty = page(f, kind, "?limit=2&before=" + latest.get("beforeCursor").asString());
            assertThat(empty.get("items").size()).isZero();
            assertThat(empty.get("beforeCursor").isNull()).isTrue();
            assertThat(empty.get("afterCursor").isNull()).isTrue();
        }
    }

    @Test
    void latestAndCursorQueriesReachBeyondTheOldThousandRecordLimit() throws Exception {
        var f = fixture(ProjectVisibility.PUBLIC);
        Instant when = Instant.parse("2026-11-19T01:00:00Z");
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            for (int i = 0; i < 1005; i++)
                                records.log(
                                        f.principal(),
                                        f.run(),
                                        new RunLogCommand(
                                                "log-" + i,
                                                RunLogLevel.INFO,
                                                RunLogSource.USER,
                                                when));
                        });
        var latest = page(f, "logs", "?limit=10&latest=true");
        assertThat(latest.get("items").get(0).get("message").asString()).isEqualTo("log-995");
        assertThat(latest.get("items").get(9).get("message").asString()).isEqualTo("log-1004");
        mvc.perform(get(base(f) + "/" + f.run().value() + "/records?limit=10&latest=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs[9].message").value("log-1004"));
        mvc.perform(get(base(f) + "/" + f.run().value() + "/records?limit=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs[0].message").value("log-0"));
        List<Long> all = new ArrayList<>();
        String query = "?limit=250";
        for (int i = 0; i < 5; i++) {
            var result = page(f, "logs", query);
            all.addAll(itemIds(result));
            if (i < 4) query = "?limit=250&after=" + result.get("nextCursor").asString();
            else assertThat(result.get("hasMore").asBoolean()).isFalse();
        }
        assertThat(all).hasSize(1005).doesNotHaveDuplicates().isSorted();
        records.log(
                f.principal(),
                f.run(),
                new RunLogCommand("new-after-page", RunLogLevel.INFO, RunLogSource.USER, when));
        var added = page(f, "logs", "?after=" + latest.get("afterCursor").asString());
        assertThat(added.get("items").size()).isEqualTo(1);
        assertThat(added.get("items").get(0).get("message").asString()).isEqualTo("new-after-page");
    }

    @Test
    void invalidOrCrossStreamCursorsAreRejectedAndAccessIsAlwaysChecked() throws Exception {
        var f = fixture(ProjectVisibility.PUBLIC);
        records.log(
                f.principal(),
                f.run(),
                new RunLogCommand("one", RunLogLevel.INFO, RunLogSource.USER, null));
        String cursor = page(f, "logs", "?latest=true").get("afterCursor").asString();
        for (String query :
                List.of(
                        "?limit=0",
                        "?limit=1001",
                        "?before=",
                        "?after=broken",
                        "?after=" + "a".repeat(513),
                        "?before=" + cursor + "&after=" + cursor,
                        "?latest=true&after=" + cursor,
                        "?latest=true&before=" + cursor))
            mvc.perform(get(base(f) + "/" + f.run().value() + "/records/logs" + query))
                    .andExpect(status().isBadRequest());
        mvc.perform(get(base(f) + "/" + f.run().value() + "/records/metrics?after=" + cursor))
                .andExpect(status().isBadRequest());
        mvc.perform(get(base(f) + "/" + f.run().value() + "/records/unknown"))
                .andExpect(status().isBadRequest());
        var other = fixture(ProjectVisibility.PUBLIC);
        mvc.perform(get(base(other) + "/" + other.run().value() + "/records/logs?after=" + cursor))
                .andExpect(status().isBadRequest());
        mvc.perform(get(base(other) + "/" + f.run().value() + "/records/logs?after=" + cursor))
                .andExpect(status().isNotFound());
        var privateRun = fixture(ProjectVisibility.PRIVATE);
        mvc.perform(
                        get(
                                base(privateRun)
                                        + "/"
                                        + privateRun.run().value()
                                        + "/records/logs?after="
                                        + cursor))
                .andExpect(status().isUnauthorized());
        var noRecords = page(other, "texts", "?latest=true");
        assertThat(noRecords.get("items").size()).isZero();
        assertThat(noRecords.get("hasMore").asBoolean()).isFalse();
    }

    private JsonNode page(Fixture f, String kind, String query) throws Exception {
        var response =
                mvc.perform(
                                get(base(f) + "/" + f.run().value() + "/records/" + kind + query)
                                        .cookie(cookie(f.owner())))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        return json.readTree(response.getContentAsString());
    }

    @Test
    void sessionReturnsOnlyTheCurrentUserProfile() throws Exception {
        var f = fixture(ProjectVisibility.PUBLIC);
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").cookie(cookie(f.owner())))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.userId").value(f.owner().getId().value()))
                .andExpect(jsonPath("$.name").value("reader"))
                .andExpect(jsonPath("$.email").value(f.owner().getEmail()))
                .andExpect(jsonPath("$.encodedPassword").doesNotExist());
    }

    @Test
    void summariesAndSingleRunCurveUseActualRecordsAndProjectAccess() throws Exception {
        var f = fixture(ProjectVisibility.PUBLIC);
        var second = runs.create(f.principal(), new RunCreateCommand("no metrics", Map.of()));
        records.metrics(
                f.principal(),
                f.run(),
                new RunMetricCommand(null, 5L, null, Map.of("custom_score", 0.5), null));
        records.metrics(
                f.principal(),
                f.run(),
                new RunMetricCommand(null, 10L, null, Map.of("custom_score", 0.8), null));
        mvc.perform(
                        get(base(f) + "/summaries")
                                .param(
                                        "runIds",
                                        second.value().toString(),
                                        f.run().value().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runId").value(second.value()))
                .andExpect(jsonPath("$[0].metrics.length()").value(0))
                .andExpect(jsonPath("$[1].lastStep").value(10))
                .andExpect(jsonPath("$[1].metrics[0].value").value(0.8));
        mvc.perform(
                        get(
                                base(f)
                                        + "/"
                                        + f.run().value()
                                        + "/metric-series?metricKey=custom_score&maxPoints=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestMetric.value").value(0.8))
                .andExpect(jsonPath("$.series.points.length()").value(2));
        var other = fixture(ProjectVisibility.PRIVATE);
        mvc.perform(
                        get(base(f) + "/summaries")
                                .param(
                                        "runIds",
                                        f.run().value().toString(),
                                        other.run().value().toString()))
                .andExpect(status().isNotFound());
        mvc.perform(
                        get(
                                base(f)
                                        + "/"
                                        + other.run().value()
                                        + "/metric-series?metricKey=custom_score"))
                .andExpect(status().isNotFound());
        mvc.perform(get(base(other) + "/summaries").param("runIds", other.run().value().toString()))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        get(
                                base(other)
                                        + "/"
                                        + other.run().value()
                                        + "/metric-series?metricKey=custom_score"))
                .andExpect(status().isUnauthorized());
        for (String selection : List.of("0", "-1", f.run().value() + "," + f.run().value(), ""))
            mvc.perform(get(base(f) + "/summaries").param("runIds", selection))
                    .andExpect(status().isBadRequest());
    }

    @Test
    void ownerCanPageAndRevokeKeysWithoutExposingSecrets() throws Exception {
        var f = fixture(ProjectVisibility.PUBLIC);
        var path = "/api/v1/projects/" + f.project().value() + "/api-keys";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).cookie(cookie(user()))).andExpect(status().isForbidden());
        var response =
                mvc.perform(get(path).cookie(cookie(f.owner())))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.totalElements").value(1))
                        .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                        .andExpect(jsonPath("$.items[0].keyHash").doesNotExist())
                        .andExpect(jsonPath("$.items[0].rawApiKey").doesNotExist())
                        .andReturn()
                        .getResponse();
        assertThat(response.getContentAsString()).doesNotContain(f.key());
        String keyId =
                json.readTree(response.getContentAsString()).at("/items/0/apiKeyId").asString();
        mvc.perform(delete(path + "/" + keyId).cookie(cookie(f.owner())))
                .andExpect(status().isForbidden());
        var csrf = new Cookie("XSRF-TOKEN", "key-test-csrf");
        mvc.perform(
                        delete(path + "/" + keyId)
                                .cookie(cookie(user()), csrf)
                                .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isForbidden());
        for (int i = 0; i < 2; i++)
            mvc.perform(
                            delete(path + "/" + keyId)
                                    .cookie(cookie(f.owner()), csrf)
                                    .header("X-XSRF-TOKEN", csrf.getValue()))
                    .andExpect(status().isNoContent());
        mvc.perform(
                        get("/api/v1/sdk/runs/" + f.run().value())
                                .header("Authorization", "Bearer " + f.key()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(path).cookie(cookie(f.owner())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("REVOKED"));
    }

    private List<Long> itemIds(JsonNode page) {
        List<Long> result = new ArrayList<>();
        for (var item : page.get("items")) result.add(item.get("id").longValue());
        return result;
    }

    User user() {
        var id = UserId.of(ids.nextId());
        owners.add(id);
        return users.save(
                User.restore(
                        id,
                        "read-" + id.value() + "@example.com",
                        "unused-hash",
                        "reader",
                        Role.USER,
                        AccountStatus.ACTIVE));
    }

    Fixture fixture(ProjectVisibility visibility) {
        var owner = user();
        var projectId = ProjectId.of(ids.nextId());
        projects.save(
                Project.create(
                        projectId, "query-test", null, owner.getId(), visibility, Instant.now()));
        var issued =
                issuance.issue(
                        owner.getId(), projectId, new IssueProjectApiKeyCommand("read-test"));
        var principal = new ProjectApiKeyPrincipal(issued.apiKeyId(), projectId, owner.getId());
        var run =
                runs.create(principal, new RunCreateCommand("first", Map.of("framework", "test")));
        return new Fixture(owner, projectId, run, principal, issued.rawApiKey());
    }

    Cookie cookie(User user) {
        return new Cookie("access_token", tokens.createAccessToken(user.getId(), user.getRole()));
    }

    String base(Fixture f) {
        return "/api/v1/projects/" + f.project().value() + "/runs";
    }

    record Fixture(
            User owner,
            ProjectId project,
            RunId run,
            ProjectApiKeyPrincipal principal,
            String key) {}
}
