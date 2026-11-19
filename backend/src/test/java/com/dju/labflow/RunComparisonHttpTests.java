package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dju.labflow.auth.infrastructure.jwt.AccessTokenProvider;
import com.dju.labflow.global.id.IdGenerator;
import com.dju.labflow.project.key.application.*;
import com.dju.labflow.project.key.domain.ProjectApiKeyPrincipal;
import com.dju.labflow.project.project.domain.*;
import com.dju.labflow.project.project.domain.repository.*;
import com.dju.labflow.run.metric.application.RunMetricCommand;
import com.dju.labflow.run.metric.infrastructure.persistence.RunMetricEntity;
import com.dju.labflow.run.parameter.application.RunParameterCommand;
import com.dju.labflow.run.run.application.*;
import com.dju.labflow.run.run.domain.RunId;
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

/** 프런트 비교 요구사항을 실제 보안 필터, HTTP, SQL 및 H2에서 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunComparisonHttpTests {
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
    final Instant when = Instant.parse("2026-11-19T00:00:00Z");

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
    void comparesActualMetricsInSelectionOrderAndSwitchesBaselineAndParameterFilter()
            throws Exception {
        var f = fixture(ProjectVisibility.PUBLIC);
        var second = runs.create(f.principal(), new RunCreateCommand("second", Map.of()));
        records.parameters(
                f.principal(),
                f.run(),
                new RunParameterCommand(
                        Map.of("batch", "64", "optimizer", "Adam", "optional", "")));
        records.parameters(
                f.principal(),
                second,
                new RunParameterCommand(Map.of("batch", "32", "optimizer", "Adam")));
        metric(f, f.run(), "val_accuracy", 5L, 0.7, 0);
        metric(f, f.run(), "val_accuracy", 10L, 0.9, 1);
        metric(f, second, "val_accuracy", 5L, 0.8, 0);
        metric(f, f.run(), "val_loss", 10L, 0.2, 1);
        runs.complete(f.principal(), f.run());
        runs.fail(f.principal(), second, new RunFailCommand("failed"));
        var result = compare(f, List.of(second, f.run()), "val_accuracy", "");
        assertThat(result.get("baselineRunId").longValue()).isEqualTo(second.value());
        assertThat(result.get("commonStep").longValue()).isEqualTo(5);
        assertThat(result.at("/runs/0/runId").longValue()).isEqualTo(second.value());
        assertThat(result.at("/runs/0/status").asString()).isEqualTo("FAILED");
        assertThat(result.at("/runs/1/status").asString()).isEqualTo("COMPLETED");
        assertThat(result.at("/runs/1/lastStep").longValue()).isEqualTo(10);
        assertThat(result.at("/runs/1/latestMetric/value").doubleValue()).isEqualTo(0.9);
        assertThat(result.at("/runs/1/commonStepMetric/value").doubleValue()).isEqualTo(0.7);
        assertThat(result.at("/runs/0/deltaFromBaseline").doubleValue()).isZero();
        assertThat(result.at("/runs/1/deltaFromBaseline").doubleValue()).isEqualTo(-0.1);
        assertThat(result.get("availableMetrics").toString()).contains("val_accuracy", "val_loss");
        assertThat(result.get("parameters").size()).isEqualTo(3);
        assertThat(result.at("/parameters/1/key").asString()).isEqualTo("optimizer");
        assertThat(result.at("/parameters/1/different").asBoolean()).isFalse();
        assertThat(result.at("/parameters/2/values/0").isNull()).isTrue();
        assertThat(result.at("/parameters/2/values/1").asString()).isEmpty();
        var switched =
                compare(
                        f,
                        List.of(second, f.run()),
                        "val_accuracy",
                        "&baselineRunId=" + f.run().value() + "&differencesOnly=true");
        assertThat(switched.at("/runs/0/deltaFromBaseline").doubleValue()).isEqualTo(0.1);
        assertThat(switched.get("parameters").size()).isEqualTo(2);
        assertThat(switched.at("/parameters/0/key").asString()).isEqualTo("batch");
        assertThat(switched.at("/parameters/1/key").asString()).isEqualTo("optional");
    }

    @Test
    void usesLargestActuallySharedStepAndNewestDuplicateByTimeThenId() throws Exception {
        var f = fixture(ProjectVisibility.PUBLIC);
        var second = runs.create(f.principal(), new RunCreateCommand("sparse", Map.of()));
        metric(f, f.run(), "loss", 5L, 0.8, 0);
        metric(f, f.run(), "loss", 20L, 0.3, 0);
        metric(f, second, "loss", 5L, 0.9, 0);
        metric(f, second, "loss", 30L, 0.2, 0);
        metric(f, f.run(), "loss", 5L, 0.7, 2);
        metric(f, f.run(), "loss", 5L, 0.6, 2);
        metric(f, f.run(), "loss", 5L, 0.95, 1);
        var result = compare(f, List.of(f.run(), second), "loss", "");
        assertThat(result.get("commonStep").longValue()).isEqualTo(5);
        assertThat(result.at("/runs/0/commonStepMetric/value").doubleValue()).isEqualTo(0.6);
        assertThat(result.at("/runs/0/latestMetric/value").doubleValue()).isEqualTo(0.6);
        assertThat(result.at("/runs/0/lastStep").longValue()).isEqualTo(20);
        assertThat(result.at("/runs/0/series/totalPoints").longValue()).isEqualTo(2);
        assertThat(result.at("/runs/0/series/points/0/value").doubleValue()).isEqualTo(0.6);
        assertThat(result.at("/runs/1/deltaFromBaseline").doubleValue()).isEqualTo(0.3);
    }

    @Test
    void requiresSharedStepAcrossAllFourRunsRatherThanOnlyTheBaseline() throws Exception {
        var f = fixture(ProjectVisibility.PUBLIC);
        List<RunId> selected = new ArrayList<>(List.of(f.run()));
        for (int i = 0; i < 3; i++)
            selected.add(runs.create(f.principal(), new RunCreateCommand("run-" + i, Map.of())));
        for (RunId run : selected) metric(f, run, "loss", 0L, 1.0, 0);
        for (int i = 0; i < 3; i++) metric(f, selected.get(i), "loss", 10L, 0.5, 1);
        metric(f, selected.getLast(), "loss", 20L, 0.4, 1);
        var result = compare(f, selected, "loss", "");
        assertThat(result.get("runs").size()).isEqualTo(4);
        assertThat(result.get("commonStep").longValue()).isZero();
    }

    @Test
    void missingMetricsAndUnalignedOrMissingStepsReturnNullWithoutInventingValues()
            throws Exception {
        var f = fixture(ProjectVisibility.PUBLIC);
        var second = runs.create(f.principal(), new RunCreateCommand("empty", Map.of()));
        metric(f, f.run(), "loss", 3L, 0.8, 0);
        metric(f, second, "loss", 7L, 0.2, 0);
        var result = compare(f, List.of(f.run(), second), "loss", "");
        assertThat(result.get("commonStep").isNull()).isTrue();
        assertThat(result.at("/runs/0/deltaFromBaseline").isNull()).isTrue();
        assertThat(result.at("/runs/1/commonStepMetric").isNull()).isTrue();
        metric(f, f.run(), "no_step", null, 0.6, 1);
        result = compare(f, List.of(f.run(), second), "no_step", "");
        assertThat(result.get("commonStep").isNull()).isTrue();
        assertThat(result.at("/runs/0/latestMetric/value").doubleValue()).isEqualTo(0.6);
        assertThat(result.at("/runs/0/lastStep").isNull()).isTrue();
        assertThat(result.at("/runs/0/series/points").isEmpty()).isTrue();
        assertThat(result.at("/runs/1/latestMetric").isNull()).isTrue();
        result = compare(f, List.of(f.run(), second), "not_logged", "");
        assertThat(result.at("/runs/0/latestMetric").isNull()).isTrue();
        assertThat(result.at("/runs/1/series/totalPoints").longValue()).isZero();
    }

    @Test
    void samplesEntireHistoryWithinLimitAndKeepsSummaryIndependentOfChart() throws Exception {
        var f = fixture(ProjectVisibility.PUBLIC);
        var second = runs.create(f.principal(), new RunCreateCommand("short", Map.of()));
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            for (int step = 0; step < 1501; step++)
                                em.persist(
                                        RunMetricEntity.builder()
                                                .id(ids.nextId())
                                                .runId(f.run().value())
                                                .key("loss")
                                                .value((double) step)
                                                .step((long) step)
                                                .recordedAt(when)
                                                .build());
                        });
        metric(f, second, "loss", 799L, 10.0, 0);
        var result = compare(f, List.of(f.run(), second), "loss", "&maxPoints=5");
        var series = result.at("/runs/0/series");
        assertThat(series.get("sampled").asBoolean()).isTrue();
        assertThat(series.get("totalPoints").longValue()).isEqualTo(1501);
        assertThat(series.get("points").size()).isEqualTo(5);
        assertThat(series.at("/points/0/step").longValue()).isZero();
        assertThat(series.at("/points/4/step").longValue()).isEqualTo(1500);
        assertThat(result.get("commonStep").longValue()).isEqualTo(799);
        assertThat(result.at("/runs/0/commonStepMetric/value").doubleValue()).isEqualTo(799);
        assertThat(result.at("/runs/0/latestMetric/value").doubleValue()).isEqualTo(1500);
        assertThat(result.at("/runs/1/deltaFromBaseline").doubleValue()).isEqualTo(-789);
        var tiny = compare(f, List.of(f.run(), second), "loss", "&maxPoints=2");
        assertThat(tiny.at("/runs/0/series/points").size()).isEqualTo(2);
        assertThat(tiny.at("/runs/0/series/points/1/step").longValue()).isEqualTo(1500);
    }

    @Test
    void privateComparisonRequiresOwnerWhilePublicComparisonAllowsAnonymousReaders()
            throws Exception {
        var f = fixture(ProjectVisibility.PRIVATE);
        var second = runs.create(f.principal(), new RunCreateCommand("second", Map.of()));
        String path = path(f, List.of(f.run(), second), "loss");
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).cookie(cookie(user()))).andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization", "Bearer " + f.key()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(path).cookie(cookie(f.owner()))).andExpect(status().isOk());
        var other = fixture(ProjectVisibility.PUBLIC);
        var otherSecond = runs.create(other.principal(), new RunCreateCommand("public", Map.of()));
        mvc.perform(get(path(other, List.of(other.run(), otherSecond), "loss")))
                .andExpect(status().isOk());
        mvc.perform(get(path(other, List.of(other.run(), f.run()), "loss")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
        mvc.perform(get(path(other, List.of(other.run(), RunId.of(ids.nextId())), "loss")))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsInvalidSelectionBaselineAndLimitsAndDocumentsEndpoint() throws Exception {
        var f = fixture(ProjectVisibility.PUBLIC);
        var second = runs.create(f.principal(), new RunCreateCommand("second", Map.of()));
        String base = "/api/v1/projects/" + f.project().value() + "/runs/compare";
        String a = f.run().value().toString();
        String b = second.value().toString();
        for (String query :
                List.of(
                        "?metricKey=loss",
                        "?runIds=" + a + "&metricKey=loss",
                        "?runIds=" + a + "," + a + "&metricKey=loss",
                        "?runIds=0," + b + "&metricKey=loss",
                        "?runIds=invalid," + b + "&metricKey=loss",
                        "?runIds=" + a + "," + b + ",1,2,3&metricKey=loss",
                        "?runIds=" + a + "," + b,
                        "?runIds=" + a + "," + b + "&metricKey=",
                        "?runIds=" + a + "," + b + "&metricKey=" + "x".repeat(101)))
            mvc.perform(get(base + query)).andExpect(status().isBadRequest());
        for (String suffix :
                List.of("&baselineRunId=1", "&baselineRunId=0", "&maxPoints=1", "&maxPoints=1001"))
            mvc.perform(get(path(f, List.of(f.run(), second), "loss") + suffix))
                    .andExpect(status().isBadRequest());
        mvc.perform(get(base).param("runIds", a, b).param("metricKey", "loss"))
                .andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.paths['/api/v1/projects/{projectId}/runs/compare'].get")
                                .exists());
    }

    private void metric(Fixture f, RunId run, String key, Long step, double value, int time) {
        records.metrics(
                f.principal(),
                run,
                new RunMetricCommand(null, step, null, Map.of(key, value), when.plusSeconds(time)));
    }

    private JsonNode compare(Fixture f, List<RunId> selected, String key, String suffix)
            throws Exception {
        var response =
                mvc.perform(get(path(f, selected, key) + suffix))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        return json.readTree(response.getContentAsString());
    }

    private String path(Fixture f, List<RunId> selected, String key) {
        return "/api/v1/projects/"
                + f.project().value()
                + "/runs/compare?runIds="
                + String.join(",", selected.stream().map(id -> id.value().toString()).toList())
                + "&metricKey="
                + key;
    }

    private User user() {
        var id = UserId.of(ids.nextId());
        owners.add(id);
        return users.save(
                User.restore(
                        id,
                        "compare-" + id.value() + "@example.com",
                        "unused-hash",
                        "reader",
                        Role.USER,
                        AccountStatus.ACTIVE));
    }

    private Fixture fixture(ProjectVisibility visibility) {
        var owner = user();
        var projectId = ProjectId.of(ids.nextId());
        projects.save(
                Project.create(
                        projectId,
                        "comparison-test",
                        null,
                        owner.getId(),
                        visibility,
                        Instant.now()));
        var issued =
                issuance.issue(
                        owner.getId(), projectId, new IssueProjectApiKeyCommand("compare-test"));
        var principal = new ProjectApiKeyPrincipal(issued.apiKeyId(), projectId, owner.getId());
        var run = runs.create(principal, new RunCreateCommand("first", Map.of()));
        return new Fixture(owner, projectId, run, principal, issued.rawApiKey());
    }

    private Cookie cookie(User user) {
        return new Cookie("access_token", tokens.createAccessToken(user.getId(), user.getRole()));
    }

    private record Fixture(
            User owner,
            ProjectId project,
            RunId run,
            ProjectApiKeyPrincipal principal,
            String key) {}
}
