package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dju.labflow.auth.application.verification.VerificationEmailSender;
import com.dju.labflow.auth.domain.*;
import com.dju.labflow.auth.infrastructure.jwt.AccessTokenProvider;
import com.dju.labflow.auth.infrastructure.jwt.JwtProperties;
import com.dju.labflow.project.key.presentation.IssueProjectApiKeyResponse;
import com.dju.labflow.project.project.domain.repository.*;
import com.dju.labflow.project.project.presentation.ProjectCreateResponse;
import com.dju.labflow.sdk.presentation.RunCreateResponse;
import com.dju.labflow.user.domain.*;
import com.dju.labflow.user.domain.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.*;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 실제 쿠키/CSRF/인증/DB 커밋을 검증한다. 이메일 전송만 대체해 외부 발송을 막는다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthHttpTests {
    private static final String BASE = "/api/v1/auth";
    private static final String PASSWORD = "test-password-123";
    private static final AtomicLong IDS = new AtomicLong(400000L);
    private final List<UserId> owners = new ArrayList<>();
    @Autowired MockMvc mvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired RefreshTokenHasher hasher;
    @Autowired JwtProperties jwtProperties;
    @Autowired ProjectRepository projects;
    @Autowired ProjectContentsRepository contents;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean VerificationEmailSender sender;

    @AfterEach
    void cleanCommittedFixtures() {
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            for (UserId owner : owners) {
                                for (var p : projects.findByCreatedBy(owner, 0, 100).items()) {
                                    var locked =
                                            projects.findByIdForUpdate(p.getId()).orElseThrow();
                                    contents.deleteByProjectId(p.getId());
                                    projects.delete(locked);
                                }
                                refreshTokens.deleteAllByUserId(owner);
                                em.createQuery(
                                                "delete from EmailVerificationEntity e where e.id = :id")
                                        .setParameter("id", owner.value())
                                        .executeUpdate();
                                em.createQuery("delete from UserEntity u where u.id = :id")
                                        .setParameter("id", owner.value())
                                        .executeUpdate();
                            }
                        });
    }

    @Test
    void loginIssuesHttpOnlyAuthCookiesWithoutTokenBodyAndPreservesCsrfCookie() throws Exception {
        User user = user(AccountStatus.ACTIVE);
        var response =
                loginResponse(user)
                        .andExpect(status().isNoContent())
                        .andExpect(content().string(""))
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andReturn()
                        .getResponse();
        Session session = session(response);
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                .filteredOn(
                        value ->
                                value.startsWith("access_token=")
                                        || value.startsWith("refresh_token="))
                .hasSize(2);
        assertThat(response.getCookie("XSRF-TOKEN")).isNotNull();
        assertCookie(session.access(), "/", false);
        assertCookie(session.refresh(), "/api/v1/auth", false);
        assertThat(session.access().getMaxAge())
                .isEqualTo(jwtProperties.accessTokenExpiration().toSeconds());
        assertThat(session.refresh().getMaxAge())
                .isPositive()
                .isLessThanOrEqualTo((int) jwtProperties.refreshTokenExpiration().toSeconds());
        var stored =
                refreshTokens
                        .findByTokenHash(hasher.hash(session.refresh().getValue()))
                        .orElseThrow();
        assertThat(stored.getUserId()).isEqualTo(user.getId());
        assertThat(stored.getTokenHash()).isNotEqualTo(session.refresh().getValue());
        mvc.perform(get("/api/v1/projects").cookie(session.access())).andExpect(status().isOk());
        mvc.perform(
                        get("/api/v1/projects")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer " + session.access().getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signupVerificationLoginProjectKeyAndSdkFlowWorksThroughHttp() throws Exception {
        String email = "journey" + IDS.incrementAndGet() + "@example.test";
        mvc.perform(
                        post(BASE + "/sign-up")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        jsonMapper.writeValueAsString(
                                                Map.of(
                                                        "email",
                                                        email,
                                                        "password",
                                                        PASSWORD,
                                                        "confirmPassword",
                                                        PASSWORD,
                                                        "name",
                                                        "journey"))))
                .andExpect(status().isCreated());
        User pending = users.findByEmail(email).orElseThrow();
        owners.add(pending.getId());
        assertThat(pending.getAccountStatus()).isEqualTo(AccountStatus.PENDING_VERIFICATION);
        loginResponse(pending).andExpect(status().isUnauthorized());
        var code = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(email), code.capture());
        withCsrf(
                        post(BASE + "/email-verifications/verify")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        jsonMapper.writeValueAsString(
                                                Map.of("email", email, "code", code.getValue()))))
                .andExpect(status().isNoContent());
        Session session = login(pending);
        var project =
                withCsrf(
                                post("/api/v1/projects")
                                        .cookie(session.access())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"name\":\"training\",\"visibility\":\"PRIVATE\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse();
        Long projectId =
                jsonMapper
                        .readValue(project.getContentAsString(), ProjectCreateResponse.class)
                        .projectId();
        var key =
                withCsrf(
                                post("/api/v1/project-api-keys/issue/" + projectId)
                                        .cookie(session.access())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"name\":\"sdk\"}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        String rawKey =
                jsonMapper
                        .readValue(key.getContentAsString(), IssueProjectApiKeyResponse.class)
                        .rawApiKey();
        var run =
                mvc.perform(
                                post("/api/v1/sdk/runs")
                                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawKey)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"name\":\"run\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse();
        Long runId =
                jsonMapper.readValue(run.getContentAsString(), RunCreateResponse.class).runId();
        mvc.perform(
                        post("/api/v1/sdk/runs/" + runId + "/metrics")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"metrics\":{\"loss\":0.125}}"))
                .andExpect(status().isNoContent());
        Session refreshed =
                session(
                        refresh(session.refresh())
                                .andExpect(status().isNoContent())
                                .andReturn()
                                .getResponse());
        logout(refreshed.refresh()).andExpect(status().isNoContent());
        refresh(refreshed.refresh()).andExpect(status().isUnauthorized());
        // 브라우저 로그아웃은 독립된 장기 SDK 키를 폐기하지 않는다.
        mvc.perform(
                        get("/api/v1/sdk/runs/" + runId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawKey))
                .andExpect(status().isOk());
    }

    @Test
    void wrongPasswordAndUnknownEmailReturnSameGeneric401WithoutAuthCookies() throws Exception {
        User user = user(AccountStatus.ACTIVE);
        var wrong =
                credentials(user.getEmail(), "wrong-password")
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                        .andReturn()
                        .getResponse();
        var missing =
                credentials("missing@example.test", PASSWORD)
                        .andExpect(status().isUnauthorized())
                        .andReturn()
                        .getResponse();
        assertThat(wrong.getContentAsString()).isEqualTo(missing.getContentAsString());
        assertThat(wrong.getCookie("access_token")).isNull();
        assertThat(wrong.getCookie("refresh_token")).isNull();
        assertThat(missing.getCookie("access_token")).isNull();
        assertThat(missing.getCookie("refresh_token")).isNull();
        assertThat(activeTokens(user.getId())).isZero();
    }

    @ParameterizedTest
    @EnumSource(
            value = AccountStatus.class,
            names = {"PENDING_VERIFICATION", "BLOCKED"})
    void inactiveAccountsCannotLogin(AccountStatus status) throws Exception {
        User user = user(status);
        loginResponse(user)
                .andExpect(status().isUnauthorized())
                .andExpect(
                        jsonPath("$.code")
                                .value(
                                        status == AccountStatus.PENDING_VERIFICATION
                                                ? "EMAIL_VERIFICATION_REQUIRED"
                                                : "UNAUTHORIZED"))
                .andExpect(cookie().doesNotExist("access_token"))
                .andExpect(cookie().doesNotExist("refresh_token"));
        assertThat(activeTokens(user.getId())).isZero();
    }

    @Test
    void pendingAccountWithWrongPasswordDoesNotRevealVerificationState() throws Exception {
        User user = user(AccountStatus.PENDING_VERIFICATION);
        credentials(user.getEmail(), "incorrect-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(cookie().doesNotExist("access_token"))
                .andExpect(cookie().doesNotExist("refresh_token"));
        assertThat(activeTokens(user.getId())).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidCredentials")
    void invalidLoginBodyIs400(String body) throws Exception {
        mvc.perform(post(BASE + "/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    static Stream<String> invalidCredentials() {
        return Stream.of(
                "{}",
                "null",
                "{",
                "{\"email\":\"bad\",\"password\":\"secret\"}",
                "{\"email\":\"x@example.test\",\"password\":null}",
                "{\"email\":\"x@example.test\",\"password\":\" \"}",
                "{\"email\":\"x@example.test\",\"password\":\"" + "a".repeat(73) + "\"}",
                "{\"email\":\"x@example.test\",\"password\":\"" + "가".repeat(25) + "\"}");
    }

    @Test
    void passwordWhitespaceIsPreservedDuringLogin() throws Exception {
        String exact = "  test-password  ";
        User user = saveUser(AccountStatus.ACTIVE, exact);
        credentials(user.getEmail(), exact).andExpect(status().isNoContent());
        credentials(user.getEmail(), exact.trim()).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRotatesCookiesAndOldTokenCannotBeReused() throws Exception {
        User user = user(AccountStatus.ACTIVE);
        Session first = login(user);
        var result =
                refresh(first.refresh())
                        .andExpect(status().isNoContent())
                        .andExpect(content().string(""))
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andReturn()
                        .getResponse();
        Session second = session(result);
        assertThat(second.refresh().getValue()).isNotEqualTo(first.refresh().getValue());
        assertThat(stored(first.refresh()).isRevoked()).isTrue();
        assertThat(stored(second.refresh()).isRevoked()).isFalse();
        refresh(first.refresh())
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        // 실패한 오래된 요청은 승리한 요청의 새 쿠키를 지우지 않는다.
        mvc.perform(get("/api/v1/projects").cookie(second.access())).andExpect(status().isOk());
        refresh(second.refresh()).andExpect(status().isNoContent());
        assertThat(activeTokens(user.getId())).isEqualTo(1);
    }

    @Test
    void refreshWorksWithExpiredAccessJwt() throws Exception {
        User user = user(AccountStatus.ACTIVE);
        Session original = login(user);
        var oldIssuer =
                new AccessTokenProvider(
                        jwtProperties, Clock.offset(Clock.systemUTC(), Duration.ofHours(-1)));
        Cookie expired =
                new Cookie("access_token", oldIssuer.createAccessToken(user.getId(), Role.USER));
        mvc.perform(get("/api/v1/projects").cookie(expired)).andExpect(status().isUnauthorized());
        var result =
                withCsrf(post(BASE + "/refresh").cookie(expired, original.refresh()))
                        .andExpect(status().isNoContent())
                        .andReturn()
                        .getResponse();
        mvc.perform(get("/api/v1/projects").cookie(session(result).access()))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"unknown-refresh"})
    void missingOrInvalidRefreshCookieIs401(String value) throws Exception {
        Cookie token = value == null ? null : new Cookie("refresh_token", value);
        refresh(token)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void refreshCannotAuthenticateWithBearerOrJsonBody() throws Exception {
        Session session = login(user(AccountStatus.ACTIVE));
        withCsrf(
                        post(BASE + "/refresh")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer " + session.refresh().getValue())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        jsonMapper.writeValueAsString(
                                                Map.of(
                                                        "refreshToken",
                                                        session.refresh().getValue()))))
                .andExpect(status().isUnauthorized());
        assertThat(stored(session.refresh()).isRevoked()).isFalse();
    }

    @Test
    void expiredRefreshIsRejectedAndLogoutStillClearsCookies() throws Exception {
        User user = user(AccountStatus.ACTIVE);
        String raw = "expired-" + IDS.incrementAndGet();
        Instant now = Instant.now();
        refreshTokens.save(
                RefreshToken.create(
                        RefreshTokenId.of(IDS.incrementAndGet()),
                        user.getId(),
                        hasher.hash(raw),
                        now.minusSeconds(60),
                        now.minusSeconds(120)));
        Cookie expired = new Cookie("refresh_token", raw);
        refresh(expired).andExpect(status().isUnauthorized());
        assertCleared(logout(expired).andExpect(status().isNoContent()).andReturn().getResponse());
        assertThat(stored(expired).isRevoked()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(
            value = AccountStatus.class,
            names = {"PENDING_VERIFICATION", "BLOCKED"})
    void inactiveOwnerCannotRefreshButCanLogout(AccountStatus status) throws Exception {
        User user = user(AccountStatus.ACTIVE);
        Session session = login(user);
        users.update(
                User.restore(
                        user.getId(),
                        user.getEmail(),
                        user.getEncodedPassword(),
                        user.getName(),
                        user.getRole(),
                        status));
        refresh(session.refresh()).andExpect(status().isUnauthorized());
        assertThat(stored(session.refresh()).isRevoked()).isFalse();
        logout(session.refresh()).andExpect(status().isNoContent());
        assertThat(stored(session.refresh()).isRevoked()).isTrue();
    }

    @Test
    void refreshAndLogoutRequireMatchingCsrfBeforeTokenMutation() throws Exception {
        Session session = login(user(AccountStatus.ACTIVE));
        for (String endpoint : List.of("/refresh", "/logout")) {
            mvc.perform(post(BASE + endpoint).cookie(session.refresh()))
                    .andExpect(status().isForbidden());
            Cookie csrf = csrf();
            mvc.perform(
                            post(BASE + endpoint)
                                    .cookie(session.refresh(), csrf)
                                    .header("X-XSRF-TOKEN", csrf.getValue() + "tampered"))
                    .andExpect(status().isForbidden());
        }
        assertThat(stored(session.refresh()).isRevoked()).isFalse();
    }

    @Test
    void logoutRevokesCurrentRefreshClearsMatchingPathsAndPreservesOtherSessions()
            throws Exception {
        User user = user(AccountStatus.ACTIVE);
        Session first = login(user);
        Session other = login(user);
        var response =
                logout(first.refresh())
                        .andExpect(status().isNoContent())
                        .andExpect(content().string(""))
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andReturn()
                        .getResponse();
        assertCleared(response);
        assertThat(stored(first.refresh()).isRevoked()).isTrue();
        refresh(first.refresh()).andExpect(status().isUnauthorized());
        assertCleared(
                logout(first.refresh())
                        .andExpect(status().isNoContent())
                        .andReturn()
                        .getResponse());
        refresh(other.refresh()).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/projects")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"unknown-refresh"})
    void logoutIsIdempotentWhenCookieIsMissingOrUnknown(String value) throws Exception {
        Cookie token = value == null ? null : new Cookie("refresh_token", value);
        assertCleared(logout(token).andExpect(status().isNoContent()).andReturn().getResponse());
    }

    @Test
    void concurrentRefreshAllowsOnlyOneRotation() throws Exception {
        User user = user(AccountStatus.ACTIVE);
        Session first = login(user);
        Cookie csrf = csrf();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<MockHttpServletResponse>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++)
                calls.add(
                        executor.submit(
                                () -> {
                                    if (!start.await(10, TimeUnit.SECONDS))
                                        throw new IllegalStateException("timeout");
                                    return mvc.perform(
                                                    post(BASE + "/refresh")
                                                            .cookie(first.refresh(), csrf)
                                                            .header(
                                                                    "X-XSRF-TOKEN",
                                                                    csrf.getValue()))
                                            .andReturn()
                                            .getResponse();
                                }));
            start.countDown();
            var responses = new ArrayList<MockHttpServletResponse>();
            for (var call : calls) responses.add(call.get(10, TimeUnit.SECONDS));
            assertThat(responses)
                    .extracting(MockHttpServletResponse::getStatus)
                    .containsExactlyInAnyOrder(204, 401);
            var success =
                    responses.stream().filter(r -> r.getStatus() == 204).findFirst().orElseThrow();
            assertThat(stored(session(success).refresh()).isRevoked()).isFalse();
            assertThat(activeTokens(user.getId())).isEqualTo(1);
        }
    }

    @Test
    void replacementInsertFailureRollsBackOldTokenRevocation() throws Exception {
        User user = user(AccountStatus.ACTIVE);
        Session first = login(user);
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status ->
                                em.createNativeQuery(
                                                "create unique index auth_refresh_probe_user on refresh_tokens(user_id)")
                                        .executeUpdate());
        try {
            refresh(first.refresh())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DATA_CONFLICT"))
                    .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
            assertThat(stored(first.refresh()).isRevoked()).isFalse();
        } finally {
            new TransactionTemplate(transactions)
                    .executeWithoutResult(
                            status ->
                                    em.createNativeQuery("drop index auth_refresh_probe_user")
                                            .executeUpdate());
        }
        refresh(first.refresh()).andExpect(status().isNoContent());
    }

    @Test
    void lockingLookupRequiresServiceTransaction() {
        assertThatThrownBy(() -> refreshTokens.findByTokenHashForUpdate("test"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void openApiShowsCookieAndCsrfContractsWithoutTokenResponseSchemas() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.security").isEmpty())
                .andExpect(
                        jsonPath("$.paths['/api/v1/auth/refresh'].post.security.length()").value(1))
                .andExpect(
                        jsonPath(
                                        "$.paths['/api/v1/auth/refresh'].post.security[0].refreshTokenCookie")
                                .exists())
                .andExpect(
                        jsonPath("$.paths['/api/v1/auth/refresh'].post.security[0].csrfToken")
                                .exists())
                .andExpect(
                        jsonPath("$.paths['/api/v1/auth/refresh'].post.security[0].cookieAuth")
                                .doesNotExist())
                .andExpect(
                        jsonPath("$.paths['/api/v1/auth/refresh'].post.parameters").doesNotExist())
                .andExpect(
                        jsonPath("$.paths['/api/v1/auth/logout'].post.security[0].csrfToken")
                                .exists())
                .andExpect(jsonPath("$.components.schemas.LoginResult").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.TokenRefreshResult").doesNotExist());
    }

    private Session login(User user) throws Exception {
        return session(
                loginResponse(user).andExpect(status().isNoContent()).andReturn().getResponse());
    }

    private ResultActions loginResponse(User user) throws Exception {
        return credentials(user.getEmail(), PASSWORD);
    }

    private ResultActions credentials(String email, String password) throws Exception {
        return mvc.perform(
                post(BASE + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                jsonMapper.writeValueAsString(
                                        Map.of("email", email, "password", password))));
    }

    private ResultActions refresh(Cookie token) throws Exception {
        return tokenRequest("/refresh", token);
    }

    private ResultActions logout(Cookie token) throws Exception {
        return tokenRequest("/logout", token);
    }

    private ResultActions tokenRequest(String path, Cookie token) throws Exception {
        var request = post(BASE + path);
        if (token != null) request.cookie(token);
        return withCsrf(request);
    }

    private ResultActions withCsrf(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        Cookie csrf = csrf();
        return mvc.perform(request.cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()));
    }

    private Cookie csrf() throws Exception {
        Cookie cookie =
                mvc.perform(get(BASE + "/csrf"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private Session session(MockHttpServletResponse response) {
        Cookie access = response.getCookie("access_token");
        Cookie refresh = response.getCookie("refresh_token");
        assertThat(access).isNotNull();
        assertThat(refresh).isNotNull();
        return new Session(access, refresh);
    }

    private void assertCookie(Cookie cookie, String path, boolean cleared) {
        assertThat(cookie.getPath()).isEqualTo(path);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isFalse();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
        if (cleared) {
            assertThat(cookie.getMaxAge()).isZero();
            assertThat(cookie.getValue()).isEmpty();
        } else assertThat(cookie.getValue()).isNotBlank();
    }

    private void assertCleared(MockHttpServletResponse response) {
        Session cleared = session(response);
        assertCookie(cleared.access(), "/", true);
        assertCookie(cleared.refresh(), "/api/v1/auth", true);
    }

    private RefreshToken stored(Cookie token) {
        return refreshTokens.findByTokenHash(hasher.hash(token.getValue())).orElseThrow();
    }

    private long activeTokens(UserId id) {
        return new TransactionTemplate(transactions)
                .execute(
                        status ->
                                em.createQuery(
                                                "select count(r) from RefreshTokenEntity r where r.userId = :id and r.revokedAt is null",
                                                Long.class)
                                        .setParameter("id", id.value())
                                        .getSingleResult());
    }

    private User user(AccountStatus state) {
        return saveUser(state, PASSWORD);
    }

    private User saveUser(AccountStatus state, String password) {
        long id = IDS.incrementAndGet();
        UserId userId = UserId.of(id);
        owners.add(userId);
        return users.save(
                User.restore(
                        userId,
                        "auth" + id + "@example.test",
                        passwords.encode(password),
                        "auth test",
                        Role.USER,
                        state));
    }

    private record Session(Cookie access, Cookie refresh) {}
}
