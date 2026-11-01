package com.dju.labflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/** JWT/공개 범위/프로젝트 소속/페이지/기록 원문을 실제 HTTP와 H2에서 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunQueryHttpTests {
    @Autowired MockMvc mvc;
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
                List.of("", "/" + f.run().value(), "/" + f.run().value() + "/records")) {
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
        for (String suffix : List.of("", "/" + f.run().value(), "/" + f.run().value() + "/records"))
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
