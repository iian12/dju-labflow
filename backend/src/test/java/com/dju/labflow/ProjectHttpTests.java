package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dju.labflow.auth.infrastructure.jwt.AccessTokenProvider;
import com.dju.labflow.project.key.application.*;
import com.dju.labflow.project.key.domain.*;
import com.dju.labflow.project.key.domain.repository.ProjectApiKeyRepository;
import com.dju.labflow.project.key.util.ProjectApiKeyHasher;
import com.dju.labflow.project.project.application.ProjectService;
import com.dju.labflow.project.project.domain.*;
import com.dju.labflow.project.project.domain.repository.*;
import com.dju.labflow.project.project.exception.ProjectNotFoundException;
import com.dju.labflow.project.project.presentation.*;
import com.dju.labflow.run.log.application.RunLogCommand;
import com.dju.labflow.run.log.domain.*;
import com.dju.labflow.run.metric.application.RunMetricCommand;
import com.dju.labflow.run.parameter.application.RunParameterCommand;
import com.dju.labflow.run.run.application.*;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.run.run.domain.repository.RunRepository;
import com.dju.labflow.run.systemmetric.application.RunSystemMetricCommand;
import com.dju.labflow.run.systemmetric.domain.GpuMetric;
import com.dju.labflow.run.systemmetric.domain.repository.RunSystemMetricRepository;
import com.dju.labflow.run.text.application.RunTextCommand;
import com.dju.labflow.user.domain.*;
import com.dju.labflow.user.domain.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
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
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 실제 JWT/CSRF, 서비스 트랜잭션과 H2로 개인 프로젝트 CRUD를 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectHttpTests {
    private static final String BASE = "/api/v1/projects";
    private static final AtomicLong IDS = new AtomicLong(300000L);
    private final List<UserId> ownerIds = new ArrayList<>();
    @Autowired MockMvc mvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired ProjectContentsRepository contents;
    @Autowired ProjectService projectService;
    @Autowired ProjectApiKeyService issuance;
    @Autowired ProjectApiKeyRepository keys;
    @Autowired ProjectApiKeyHasher hasher;
    @Autowired AccessTokenProvider tokens;
    @Autowired RunService runService;
    @Autowired RunRecordService records;
    @Autowired RunRepository runs;
    @Autowired RunSystemMetricRepository systemMetrics;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;

    @AfterEach
    void cleanCommittedFixtures() {
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            for (UserId owner : ownerIds) {
                                for (Project p : projects.findByCreatedBy(owner, 0, 100).items()) {
                                    Project locked =
                                            projects.findByIdForUpdate(p.getId()).orElseThrow();
                                    contents.deleteByProjectId(p.getId());
                                    projects.delete(locked);
                                }
                                em.createQuery("delete from UserEntity u where u.id = :id")
                                        .setParameter("id", owner.value())
                                        .executeUpdate();
                            }
                        });
    }

    @Test
    void ownerCanCreateReadListUpdateAndDelete() throws Exception {
        User owner = user();
        ProjectId id = create(owner, ProjectVisibility.PRIVATE);
        ProjectResponse first = detail(owner, id);
        assertThat(first.createdBy()).isEqualTo(owner.getId().value());
        assertThat(first.version()).isZero();
        send(owner, HttpMethod.GET, BASE, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].projectId").value(id.value()))
                .andExpect(jsonPath("$.totalElements").value(1));
        send(
                        owner,
                        HttpMethod.PUT,
                        path(id),
                        update("renamed", ProjectVisibility.PUBLIC, first.version()))
                .andExpect(status().isNoContent());
        ProjectResponse changed = detail(owner, id);
        assertThat(changed.name()).isEqualTo("renamed");
        assertThat(changed.visibility()).isEqualTo(ProjectVisibility.PUBLIC);
        assertThat(changed.createdAt()).isEqualTo(first.createdAt());
        assertThat(changed.createdBy()).isEqualTo(first.createdBy());
        assertThat(changed.updatedAt()).isNotNull();
        assertThat(changed.version()).isGreaterThan(first.version());
        send(owner, HttpMethod.DELETE, path(id) + "?version=" + changed.version(), null)
                .andExpect(status().isNoContent());
        mvc.perform(get(path(id))).andExpect(status().isNotFound());
        send(owner, HttpMethod.GET, BASE, null).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void visibilityControlsAnonymousAndOtherUserReads() throws Exception {
        User owner = user();
        User other = user();
        ProjectId publicId = create(owner, ProjectVisibility.PUBLIC);
        ProjectId privateId = create(owner, ProjectVisibility.PRIVATE);
        mvc.perform(get(path(publicId))).andExpect(status().isOk());
        send(other, HttpMethod.GET, path(publicId), null).andExpect(status().isOk());
        mvc.perform(get(path(privateId)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        send(other, HttpMethod.GET, path(privateId), null).andExpect(status().isForbidden());
        send(owner, HttpMethod.GET, path(privateId), null).andExpect(status().isOk());
        other.promoteToAdmin();
        users.update(other);
        send(other, HttpMethod.GET, path(privateId), null).andExpect(status().isForbidden());
    }

    @Test
    void personalListIsPaginatedAndNeverIncludesOtherOwnersPublicProjects() throws Exception {
        User owner = user();
        User other = user();
        ProjectId first = create(owner, ProjectVisibility.PRIVATE);
        ProjectId second = create(owner, ProjectVisibility.PUBLIC);
        create(other, ProjectVisibility.PUBLIC);
        send(owner, HttpMethod.GET, BASE + "?page=0&size=1", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].projectId").value(second.value()))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
        send(owner, HttpMethod.GET, BASE + "?page=1&size=1", null)
                .andExpect(jsonPath("$.items[0].projectId").value(first.value()));
        send(owner, HttpMethod.GET, BASE + "?page=2&size=1", null)
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }

    @Test
    void emptyListHasZeroPagesAndDefaultPageSize() throws Exception {
        send(user(), HttpMethod.GET, BASE, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void ownershipCannotBeSpoofedAndOtherUsersCannotManagePublicProjects() throws Exception {
        User owner = user();
        User other = user();
        var result =
                send(
                                owner,
                                HttpMethod.POST,
                                BASE + "?userId=" + other.getId().value(),
                                "{\"name\":\"test\",\"visibility\":\"PUBLIC\"}")
                        .andExpect(status().isCreated())
                        .andReturn();
        ProjectId id =
                ProjectId.of(
                        jsonMapper
                                .readValue(
                                        result.getResponse().getContentAsString(),
                                        ProjectCreateResponse.class)
                                .projectId());
        assertThat(detail(owner, id).createdBy()).isEqualTo(owner.getId().value());
        send(other, HttpMethod.PUT, path(id), update("stolen", ProjectVisibility.PUBLIC, 0L))
                .andExpect(status().isForbidden());
        send(other, HttpMethod.DELETE, path(id) + "?version=0", null)
                .andExpect(status().isForbidden());
        assertThat(detail(owner, id).name()).isEqualTo("test");
    }

    @Test
    void staleUpdateAndDeleteAre409BeforeAnyDataIsRemoved() throws Exception {
        User owner = user();
        ProjectId id = create(owner, ProjectVisibility.PUBLIC);
        Graph graph = graph(owner, id);
        send(owner, HttpMethod.PUT, path(id), update("private", ProjectVisibility.PRIVATE, 0L))
                .andExpect(status().isNoContent());
        send(owner, HttpMethod.PUT, path(id), update("stale", ProjectVisibility.PUBLIC, 0L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_STATE"));
        send(owner, HttpMethod.DELETE, path(id) + "?version=0", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_STATE"));
        assertThat(detail(owner, id).visibility()).isEqualTo(ProjectVisibility.PRIVATE);
        assertThat(runs.findById(graph.runId())).isPresent();
        assertThat(keys.findByKeyHash(hasher.hash(graph.rawKey()))).isPresent();
    }

    @Test
    void mutationsRequireCsrfAndUserAuthenticationRatherThanSdkKey() throws Exception {
        User owner = user();
        ProjectId id = create(owner, ProjectVisibility.PUBLIC);
        Graph graph = graph(owner, id);
        Cookie jwt = cookie(owner);
        mvc.perform(
                        post(BASE)
                                .cookie(jwt)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"x\",\"visibility\":\"PRIVATE\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        put(path(id))
                                .cookie(jwt)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(update("x", ProjectVisibility.PRIVATE, 0L)))
                .andExpect(status().isForbidden());
        mvc.perform(delete(path(id)).cookie(jwt).param("version", "0"))
                .andExpect(status().isForbidden());
        mvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, "Bearer " + graph.rawKey()))
                .andExpect(status().isUnauthorized());
        String csrf = UUID.randomUUID().toString();
        mvc.perform(
                        post(BASE)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + graph.rawKey())
                                .cookie(new Cookie("XSRF-TOKEN", csrf))
                                .header("X-XSRF-TOKEN", csrf)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"x\",\"visibility\":\"PRIVATE\"}"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @EnumSource(
            value = AccountStatus.class,
            names = {"BLOCKED", "PENDING_VERIFICATION"})
    void nonActiveUsersCannotCreateOrManageProjects(AccountStatus state) throws Exception {
        User owner = user();
        ProjectId id = create(owner, ProjectVisibility.PRIVATE);
        users.update(
                User.restore(
                        owner.getId(),
                        owner.getEmail(),
                        owner.getEncodedPassword(),
                        owner.getName(),
                        owner.getRole(),
                        state));
        send(owner, HttpMethod.POST, BASE, "{\"name\":\"x\",\"visibility\":\"PUBLIC\"}")
                .andExpect(status().isUnauthorized());
        send(owner, HttpMethod.PUT, path(id), update("x", ProjectVisibility.PUBLIC, 0L))
                .andExpect(status().isUnauthorized());
        send(owner, HttpMethod.DELETE, path(id) + "?version=0", null)
                .andExpect(status().isUnauthorized());
        send(owner, HttpMethod.GET, BASE, null).andExpect(status().isUnauthorized());
        send(owner, HttpMethod.GET, path(id), null).andExpect(status().isUnauthorized());
    }

    @Test
    void deletionRemovesEveryDependentRecordAndInvalidatesKeysWithoutTouchingOtherProjects()
            throws Exception {
        User owner = user();
        ProjectId id = create(owner, ProjectVisibility.PUBLIC);
        Graph graph = graph(owner, id);
        ProjectId otherId = create(owner, ProjectVisibility.PRIVATE);
        Graph other = graph(owner, otherId);
        send(owner, HttpMethod.DELETE, path(id) + "?version=0", null)
                .andExpect(status().isNoContent());
        assertThat(projects.findById(id)).isEmpty();
        assertThat(runs.findById(graph.runId())).isEmpty();
        assertThat(keys.findByKeyHash(hasher.hash(graph.rawKey()))).isEmpty();
        for (String table :
                List.of(
                        "run_parameter_entity",
                        "run_metric_entity",
                        "run_log_entity",
                        "run_text_entity",
                        "run_system_metric_entity",
                        "run_metadata"))
            assertThat(count(table, "run_id", graph.runId().value())).as(table).isZero();
        assertThat(count("run_system_metric_gpu", "system_metric_id", graph.systemMetricId()))
                .isZero();
        assertThat(runs.findById(other.runId())).isPresent();
        assertThat(keys.findByKeyHash(hasher.hash(other.rawKey()))).isPresent();
        mvc.perform(
                        get("/api/v1/sdk/runs/" + graph.runId().value())
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + graph.rawKey()))
                .andExpect(status().isUnauthorized());
        send(owner, HttpMethod.DELETE, path(id) + "?version=0", null)
                .andExpect(status().isNotFound());
    }

    @Test
    void failedFinalProjectDeleteRollsBackKeysRunsAndCollections() throws Exception {
        User owner = user();
        ProjectId id = create(owner, ProjectVisibility.PUBLIC);
        Graph graph = graph(owner, id);
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            em.createNativeQuery(
                                            "create table project_delete_guard(project_id bigint primary key references project_entity(id))")
                                    .executeUpdate();
                            em.createNativeQuery(
                                            "insert into project_delete_guard(project_id) values(:id)")
                                    .setParameter("id", id.value())
                                    .executeUpdate();
                        });
        try {
            send(owner, HttpMethod.DELETE, path(id) + "?version=0", null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DATA_CONFLICT"));
            assertThat(projects.findById(id)).isPresent();
            assertThat(runs.findById(graph.runId())).isPresent();
            assertThat(keys.findByKeyHash(hasher.hash(graph.rawKey()))).isPresent();
            assertThat(count("run_metadata", "run_id", graph.runId().value())).isEqualTo(1);
            assertThat(systemMetrics.findByRunId(graph.runId(), 10))
                    .singleElement()
                    .satisfies(s -> assertThat(s.getGpus()).hasSize(1));
        } finally {
            new TransactionTemplate(transactions)
                    .executeWithoutResult(
                            status ->
                                    em.createNativeQuery("drop table project_delete_guard")
                                            .executeUpdate());
        }
    }

    @Test
    void inFlightRecordWaitingForProjectDeletionCannotCreateOrphan() throws Exception {
        User owner = user();
        ProjectId id = create(owner, ProjectVisibility.PUBLIC);
        Graph graph = graph(owner, id);
        var deleted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var deletion =
                    executor.submit(
                            () ->
                                    new TransactionTemplate(transactions)
                                            .executeWithoutResult(
                                                    status -> {
                                                        projectService.delete(
                                                                owner.getId(), id, 0L);
                                                        deleted.countDown();
                                                        await(release);
                                                    }));
            try {
                assertThat(deleted.await(10, TimeUnit.SECONDS)).isTrue();
                var append =
                        executor.submit(
                                () -> {
                                    started.countDown();
                                    return records.metrics(
                                            graph.principal(),
                                            graph.runId(),
                                            new RunMetricCommand(
                                                    null, 1L, null, Map.of("loss", 0.2), null));
                                });
                assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                release.countDown();
                deletion.get(10, TimeUnit.SECONDS);
                assertThatThrownBy(() -> append.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(ProjectNotFoundException.class);
            } finally {
                release.countDown();
            }
        }
        assertThat(count("run_metric_entity", "run_id", graph.runId().value())).isZero();
    }

    @Test
    void keyIssuanceBeforeDeletionIsIncludedInDeletion() throws Exception {
        User owner = user();
        ProjectId id = create(owner, ProjectVisibility.PRIVATE);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var deleting = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var issue =
                    executor.submit(
                            () ->
                                    new TransactionTemplate(transactions)
                                            .execute(
                                                    status -> {
                                                        projects.findByIdForUpdate(id)
                                                                .orElseThrow();
                                                        locked.countDown();
                                                        await(release);
                                                        return issuance.issue(
                                                                owner.getId(),
                                                                id,
                                                                new IssueProjectApiKeyCommand(
                                                                        "in flight"));
                                                    }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var deletion =
                        executor.submit(
                                () -> {
                                    deleting.countDown();
                                    projectService.delete(owner.getId(), id, 0L);
                                });
                assertThat(deleting.await(10, TimeUnit.SECONDS)).isTrue();
                release.countDown();
                var issued = issue.get(10, TimeUnit.SECONDS);
                deletion.get(10, TimeUnit.SECONDS);
                assertThat(keys.findByKeyHash(hasher.hash(issued.rawApiKey()))).isEmpty();
            } finally {
                release.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "0", "abc", "9223372036854775808"})
    void malformedProjectIdsAre400(String id) throws Exception {
        User owner = user();
        mvc.perform(get(BASE + "/" + id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        send(owner, HttpMethod.PUT, BASE + "/" + id, update("x", ProjectVisibility.PUBLIC, 0L))
                .andExpect(status().isBadRequest());
        send(owner, HttpMethod.DELETE, BASE + "/" + id + "?version=0", null)
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=abc"})
    void invalidPaginationIs400(String query) throws Exception {
        send(user(), HttpMethod.GET, BASE + "?" + query, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidBodiesAre400(String body) throws Exception {
        User owner = user();
        ProjectId id = create(owner, ProjectVisibility.PUBLIC);
        send(owner, HttpMethod.POST, BASE, body).andExpect(status().isBadRequest());
        send(owner, HttpMethod.PUT, path(id), body).andExpect(status().isBadRequest());
    }

    static Stream<String> invalidBodies() {
        return Stream.of(
                "{}",
                "null",
                "{",
                "{\"name\":\" \",\"visibility\":\"PRIVATE\",\"version\":0}",
                "{\"name\":\"x\",\"visibility\":\"TEAM\",\"version\":0}",
                "{\"name\":\"x\",\"visibility\":null,\"version\":0}",
                "{\"name\":\"" + "x".repeat(101) + "\",\"visibility\":\"PUBLIC\",\"version\":0}",
                "{\"name\":\"x\",\"description\":\""
                        + "x".repeat(2001)
                        + "\",\"visibility\":\"PUBLIC\",\"version\":0}");
    }

    @Test
    void updateAndDeleteRequireValidVersionsAndMissingResourcesReturn404() throws Exception {
        User owner = user();
        ProjectId id = create(owner, ProjectVisibility.PUBLIC);
        send(owner, HttpMethod.PUT, path(id), "{\"name\":\"x\",\"visibility\":\"PRIVATE\"}")
                .andExpect(status().isBadRequest());
        send(owner, HttpMethod.PUT, path(id), update("x", ProjectVisibility.PRIVATE, -1L))
                .andExpect(status().isBadRequest());
        for (String query : List.of("", "?version=-1", "?version=abc"))
            send(owner, HttpMethod.DELETE, path(id) + query, null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(get(BASE + "/1")).andExpect(status().isNotFound());
        send(owner, HttpMethod.PUT, BASE + "/1", update("x", ProjectVisibility.PUBLIC, 0L))
                .andExpect(status().isNotFound());
        send(owner, HttpMethod.DELETE, BASE + "/1?version=0", null)
                .andExpect(status().isNotFound());
    }

    @Test
    void openApiDescribesCrudVersionsAndPublicDetailSecurity() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/projects'].post.responses['201']").exists())
                .andExpect(
                        jsonPath("$.paths['/api/v1/projects'].get.security[0].cookieAuth").exists())
                .andExpect(
                        jsonPath("$.paths['/api/v1/projects/{projectId}'].get.security").isEmpty())
                .andExpect(
                        jsonPath("$.paths['/api/v1/projects/{projectId}'].put.responses['204']")
                                .exists())
                .andExpect(
                        jsonPath(
                                        "$.paths['/api/v1/projects/{projectId}'].delete.parameters[1].name")
                                .value("version"))
                // 보안 설정이 생략된 변경 작업은 루트의 cookieAuth AND csrfToken을 상속한다.
                .andExpect(
                        jsonPath("$.paths['/api/v1/projects/{projectId}'].put.security")
                                .doesNotExist())
                .andExpect(jsonPath("$.security[0].cookieAuth").exists())
                .andExpect(jsonPath("$.security[0].csrfToken").exists())
                .andExpect(
                        jsonPath(
                                "$.components.schemas.ProjectUpdateRequest.required",
                                org.hamcrest.Matchers.hasItem("version")))
                .andExpect(
                        jsonPath("$.paths['/api/v1/projects/{projectId}'].put.parameters.length()")
                                .value(1));
    }

    private Graph graph(User owner, ProjectId projectId) {
        var issued =
                issuance.issue(owner.getId(), projectId, new IssueProjectApiKeyCommand("test"));
        var principal = new ProjectApiKeyPrincipal(issued.apiKeyId(), projectId, owner.getId());
        RunId run =
                runService.create(
                        principal, new RunCreateCommand("run", Map.of("framework", "pytorch")));
        records.parameters(principal, run, new RunParameterCommand(Map.of("optimizer", "adam")));
        records.metrics(
                principal, run, new RunMetricCommand(null, 1L, null, Map.of("loss", 0.1), null));
        records.log(
                principal,
                run,
                new RunLogCommand("start", RunLogLevel.INFO, RunLogSource.USER, null));
        records.text(principal, run, new RunTextCommand("prediction", "cat", 1L, null));
        var system =
                records.systemMetric(
                        principal,
                        run,
                        new RunSystemMetricCommand(
                                10.0,
                                1L,
                                10L,
                                List.of(new GpuMetric(0, 20.0, 1L, 10L)),
                                null,
                                null));
        return new Graph(principal, run, system.value(), issued.rawApiKey());
    }

    private long count(String table, String column, Long id) {
        return new TransactionTemplate(transactions)
                .execute(
                        status ->
                                ((Number)
                                                em.createNativeQuery(
                                                                "select count(*) from "
                                                                        + table
                                                                        + " where "
                                                                        + column
                                                                        + " = :id")
                                                        .setParameter("id", id)
                                                        .getSingleResult())
                                        .longValue());
    }

    private User user() {
        long id = IDS.incrementAndGet();
        ownerIds.add(UserId.of(id));
        return users.save(
                User.restore(
                        UserId.of(id),
                        "project" + id + "@example.test",
                        "encoded",
                        "test",
                        Role.USER,
                        AccountStatus.ACTIVE));
    }

    private ProjectId create(User owner, ProjectVisibility visibility) throws Exception {
        var response =
                send(
                                owner,
                                HttpMethod.POST,
                                BASE,
                                jsonMapper.writeValueAsString(
                                        Map.of("name", "project", "visibility", visibility)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse();
        Long id =
                jsonMapper
                        .readValue(response.getContentAsString(), ProjectCreateResponse.class)
                        .projectId();
        assertThat(response.getHeader(HttpHeaders.LOCATION))
                .isEqualTo("http://localhost" + BASE + "/" + id);
        return ProjectId.of(id);
    }

    private ProjectResponse detail(User owner, ProjectId id) throws Exception {
        var response =
                send(owner, HttpMethod.GET, path(id), null)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        return jsonMapper.readValue(response.getContentAsString(), ProjectResponse.class);
    }

    private ResultActions send(User owner, HttpMethod method, String path, String body)
            throws Exception {
        String csrf = UUID.randomUUID().toString();
        var request =
                request(method, path)
                        .cookie(cookie(owner), new Cookie("XSRF-TOKEN", csrf))
                        .header("X-XSRF-TOKEN", csrf);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request);
    }

    private Cookie cookie(User owner) {
        return new Cookie("access_token", tokens.createAccessToken(owner.getId(), owner.getRole()));
    }

    private String path(ProjectId id) {
        return BASE + "/" + id.value();
    }

    private String update(String name, ProjectVisibility visibility, Long version)
            throws Exception {
        return jsonMapper.writeValueAsString(
                Map.of("name", name, "visibility", visibility, "version", version));
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private record Graph(
            ProjectApiKeyPrincipal principal, RunId runId, Long systemMetricId, String rawKey) {}
}
