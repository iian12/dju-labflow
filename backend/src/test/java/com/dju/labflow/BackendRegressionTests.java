package com.dju.labflow;

import com.dju.labflow.auth.application.AuthService;
import com.dju.labflow.auth.application.LoginCommand;
import com.dju.labflow.auth.application.SignUpCommand;
import com.dju.labflow.auth.application.token.TokenRefreshService;
import com.dju.labflow.auth.domain.RefreshTokenHasher;
import com.dju.labflow.auth.domain.RefreshTokenRepository;
import com.dju.labflow.auth.infrastructure.jwt.AccessTokenProvider;
import com.dju.labflow.project.project.domain.Project;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.project.project.domain.ProjectVisibility;
import com.dju.labflow.project.project.domain.repository.ProjectRepository;
import com.dju.labflow.user.domain.AccountStatus;
import com.dju.labflow.user.domain.Role;
import com.dju.labflow.user.domain.User;
import com.dju.labflow.user.domain.UserId;
import com.dju.labflow.user.domain.repository.UserRepository;
import com.dju.labflow.user.exception.DuplicateEmailException;
import com.dju.labflow.user.infrastructure.security.CustomUserDetailsService;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.web.FilterChainProxy;
import com.dju.labflow.auth.infrastructure.persistence.JwtAuthenticationFilter;
import com.dju.labflow.project.key.infrastructure.security.ProjectApiKeyAuthenticationFilter;
import com.dju.labflow.project.key.application.ProjectApiKeyService;
import com.dju.labflow.project.key.application.IssueProjectApiKeyCommand;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** H2와 실제 Security/MVC/JPA 구성을 사용해 수정한 동작을 요청부터 저장까지 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BackendRegressionTests {
    private static final String PASSWORD = "test-password-123";
    private static final String ISSUE_URL = "/api/v1/project-api-keys/issue/10";
    @Autowired AuthService authService;
    @Autowired TokenRefreshService tokenRefreshService;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired RefreshTokenHasher refreshTokenHasher;
    @Autowired CustomUserDetailsService userDetailsService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired AccessTokenProvider accessTokens;
    @Autowired EntityManager entityManager;
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;
    @Autowired ProjectApiKeyService keyService;

    @Test
    void activeAccountCanLoginAndRotateTokens() {
        User user = saveUser(1L, AccountStatus.ACTIVE);
        var login = authService.login(new LoginCommand(user.getEmail(), PASSWORD));
        var refreshed = tokenRefreshService.refresh(login.refreshToken());
        assertThat(refreshed.refreshToken()).isNotEqualTo(login.refreshToken());
        assertThat(refreshTokens.findByTokenHash(refreshTokenHasher.hash(login.refreshToken()))
                .orElseThrow().isRevoked()).isTrue();
        assertThat(refreshTokens.findByTokenHash(refreshTokenHasher.hash(refreshed.refreshToken()))).isPresent();
    }

    @Test
    void blockedAccountCannotLogin() {
        User user = saveUser(1L, AccountStatus.BLOCKED);
        assertThatThrownBy(() -> authService.login(new LoginCommand(user.getEmail(), PASSWORD)))
                .isInstanceOf(LockedException.class);
    }

    @Test
    void pendingAccountCannotLogin() {
        User user = saveUser(1L, AccountStatus.PENDING_VERIFICATION);
        assertThatThrownBy(() -> authService.login(new LoginCommand(user.getEmail(), PASSWORD)))
                .isInstanceOf(DisabledException.class);
    }

    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"BLOCKED", "PENDING_VERIFICATION"})
    void nonActiveAccountCannotRefreshOrConsumeExistingToken(AccountStatus status) {
        User user = saveUser(1L, AccountStatus.ACTIVE);
        var login = authService.login(new LoginCommand(user.getEmail(), PASSWORD));
        users.update(User.restore(user.getId(), user.getEmail(), user.getEncodedPassword(), user.getName(), user.getRole(), status));
        assertThatThrownBy(() -> tokenRefreshService.refresh(login.refreshToken()))
                .isInstanceOf(status == AccountStatus.BLOCKED ? LockedException.class : DisabledException.class);
        assertThat(refreshTokens.findByTokenHash(refreshTokenHasher.hash(login.refreshToken()))
                .orElseThrow().isRevoked()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"BLOCKED", "PENDING_VERIFICATION"})
    void issuedJwtStopsWorkingOnNextRequestWhenAccountBecomesInactive(AccountStatus status) throws Exception {
        User user = saveUser(1L, AccountStatus.ACTIVE);
        saveProject(user);
        Cookie cookie = accessCookie(user);
        users.update(User.restore(user.getId(), user.getEmail(), user.getEncodedPassword(), user.getName(), user.getRole(), status));
        mvc.perform(post(ISSUE_URL).cookie(cookie).with(csrf()).contentType("application/json").content("{\"name\":\"training\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void missingUserUsesSecurityExceptionAndGenericBadCredentials() {
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("missing@example.test"))
                .isInstanceOf(UsernameNotFoundException.class);
        assertThatThrownBy(() -> authService.login(new LoginCommand("missing@example.test", PASSWORD)))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void userPromotionAndDemotionArePersisted() {
        User user = saveUser(1L, AccountStatus.ACTIVE);
        user.promoteToAdmin();
        users.update(user);
        entityManager.flush();
        entityManager.clear();
        User promoted = users.findById(user.getId()).orElseThrow();
        assertThat(promoted.getRole()).isEqualTo(Role.ADMIN);
        promoted.revokeAdmin();
        users.update(promoted);
        entityManager.flush();
        entityManager.clear();
        assertThat(users.findById(user.getId()).orElseThrow().getRole()).isEqualTo(Role.USER);
    }

    @Test
    void projectUpdatePersistsMutableFieldsAndPreservesIdentity() {
        User owner = saveUser(1L, AccountStatus.ACTIVE);
        Project project = saveProject(owner);
        Instant updatedAt = project.getCreatedAt().plusSeconds(5);
        project.updateDetails("updated", "new description", ProjectVisibility.PUBLIC, updatedAt);
        projects.update(project);
        entityManager.flush();
        entityManager.clear();
        Project loaded = projects.findById(project.getId()).orElseThrow();
        assertThat(loaded.getName()).isEqualTo("updated");
        assertThat(loaded.getDescription()).isEqualTo("new description");
        assertThat(loaded.getVisibility()).isEqualTo(ProjectVisibility.PUBLIC);
        assertThat(loaded.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(loaded.getCreatedBy()).isEqualTo(owner.getId());
        assertThat(loaded.getCreatedAt()).isEqualTo(project.getCreatedAt());
    }

    @Test
    void signupRejectsNullPasswordInvalidEmailAndOverlongUtf8Password() {
        assertThatThrownBy(() -> authService.signUp(new SignUpCommand("a@example.test", null, null, "name")))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> authService.signUp(new SignUpCommand("not-email", PASSWORD, PASSWORD, "name")))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> authService.signUp(new SignUpCommand("a@example.test", "가".repeat(25), "가".repeat(25), "name")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void signupCreatesPendingAccountAndRejectsDuplicateEmail() {
        var command = new SignUpCommand("new@example.test", PASSWORD, PASSWORD, "new user");
        UserId id = authService.signUp(command);
        User user = users.findById(id).orElseThrow();
        assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.PENDING_VERIFICATION);
        assertThat(passwordEncoder.matches(PASSWORD, user.getEncodedPassword())).isTrue();
        assertThatThrownBy(() -> authService.signUp(command)).isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    void keyIssuanceRejectsInvalidBodyAndMalformedJson() throws Exception {
        User user = saveUser(1L, AccountStatus.ACTIVE);
        saveProject(user);
        mvc.perform(post(ISSUE_URL).cookie(accessCookie(user)).with(csrf()).contentType("application/json").content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.name").exists());
        mvc.perform(post(ISSUE_URL).cookie(accessCookie(user)).with(csrf()).contentType("application/json").content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(post("/api/v1/project-api-keys/issue/-1").cookie(accessCookie(user)).with(csrf()).contentType("application/json").content("{\"name\":\"test\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingProjectReturns404() throws Exception {
        User user = saveUser(1L, AccountStatus.ACTIVE);
        mvc.perform(post(ISSUE_URL).cookie(accessCookie(user)).with(csrf()).contentType("application/json").content("{\"name\":\"test\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    @Test
    void requesterCannotImpersonateProjectOwnerUsingQueryParameter() throws Exception {
        User owner = saveUser(1L, AccountStatus.ACTIVE);
        User other = saveUser(2L, AccountStatus.ACTIVE);
        saveProject(owner);
        mvc.perform(post(ISSUE_URL).param("value", "1").cookie(accessCookie(other)).with(csrf()).contentType("application/json").content("{\"name\":\"test\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(ISSUE_URL).cookie(accessCookie(owner)).with(csrf()).contentType("application/json").content("{\"name\":\"test\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rawApiKey").value(org.hamcrest.Matchers.startsWith("lfp_")))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void keyIssuanceRequiresCsrfAndBootstrapEndpointSetsCookie() throws Exception {
        User user = saveUser(1L, AccountStatus.ACTIVE);
        saveProject(user);
        mvc.perform(post(ISSUE_URL).cookie(accessCookie(user)).contentType("application/json").content("{\"name\":\"test\"}"))
                .andExpect(status().isForbidden());
        var response = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk())
                .andExpect(cookie().exists("XSRF-TOKEN")).andReturn().getResponse();
        Cookie csrfCookie = response.getCookie("XSRF-TOKEN");
        mvc.perform(post(ISSUE_URL).cookie(accessCookie(user), csrfCookie).header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType("application/json").content("{\"name\":\"test\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void swaggerAndOpenApiAreAccessibleAndDocumentRealContract() throws Exception {
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs"));
        // springdoc은 CSRF 설정을 JSON이 아니라 브라우저의 요청 인터셉터로 생성한다.
        mvc.perform(get("/swagger-ui/swagger-initializer.js")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("requestInterceptor")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("document.cookie")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("XSRF-TOKEN")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("X-XSRF-TOKEN")));
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/project-api-keys/issue/{projectId}'].post.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/project-api-keys/issue/{projectId}'].post.parameters.length()").value(1))
                .andExpect(jsonPath("$.paths['/api/v1/auth/csrf'].get.security").isEmpty())
                .andExpect(jsonPath("$.components.schemas.IssueProjectApiKeyRequest.properties.name.maxLength").value(100))
                .andExpect(jsonPath("$.security[0].cookieAuth").exists())
                .andExpect(jsonPath("$.security[0].csrfToken").exists());
    }

    @Test
    void projectDomainRejectsInvalidInputWithoutPartiallyUpdatingState() {
        var owner = UserId.of(1L);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        assertThatThrownBy(() -> Project.create(ProjectId.of(10L), " ", null, owner, ProjectVisibility.PRIVATE, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Project.create(ProjectId.of(10L), "test", null, owner, null, now))
                .isInstanceOf(IllegalArgumentException.class);
        Project project = Project.create(ProjectId.of(10L), "original", null, owner, ProjectVisibility.PRIVATE, now);
        assertThatThrownBy(() -> project.updateDetails("changed", "x".repeat(2001), ProjectVisibility.PUBLIC, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(project.getName()).isEqualTo("original");
        assertThat(project.getVisibility()).isEqualTo(ProjectVisibility.PRIVATE);
    }

    private User saveUser(long id, AccountStatus status) {
        return users.save(User.restore(UserId.of(id), "user" + id + "@example.test",
                passwordEncoder.encode(PASSWORD), "test user", Role.USER, status));
    }

    @Test
    void authenticationFiltersHaveOnlyOneSecurityChainRegistration() {
        assertThat(context.getBean("jwtFilterRegistration", FilterRegistrationBean.class).isEnabled()).isFalse();
        assertThat(context.getBean("apiKeyFilterRegistration", FilterRegistrationBean.class).isEnabled()).isFalse();
        var filters = context.getBean(FilterChainProxy.class).getFilters(ISSUE_URL);
        assertThat(filters.stream().filter(JwtAuthenticationFilter.class::isInstance).count()).isEqualTo(1);
        assertThat(filters.stream().filter(ProjectApiKeyAuthenticationFilter.class::isInstance).count()).isEqualTo(1);
    }

    @Test
    void apiKeyCommandIsValidatedWithoutHttpController() {
        assertThatThrownBy(() -> keyService.issue(UserId.of(1L), ProjectId.of(10L), new IssueProjectApiKeyCommand(" ")))
                .isInstanceOf(ConstraintViolationException.class);
    }

    private Project saveProject(User owner) {
        return projects.save(Project.create(ProjectId.of(10L), "test project", "description", owner.getId(),
                ProjectVisibility.PRIVATE, Instant.parse("2026-01-01T00:00:00Z")));
    }

    private Cookie accessCookie(User user) {
        return new Cookie("access_token", accessTokens.createAccessToken(user.getId(), user.getRole()));
    }

    /** 테스트용 저장소로 교체하지 않고 실제 쿠키/헤더 방식으로 CSRF를 검증한다. */
    private org.springframework.test.web.servlet.request.RequestPostProcessor csrf() {
        return request -> {
            String token = java.util.UUID.randomUUID().toString();
            Cookie[] existing = request.getCookies();
            Cookie[] cookies = java.util.Arrays.copyOf(existing == null ? new Cookie[0] : existing,
                    existing == null ? 1 : existing.length + 1);
            cookies[cookies.length - 1] = new Cookie("XSRF-TOKEN", token);
            request.setCookies(cookies);
            request.addHeader("X-XSRF-TOKEN", token);
            return request;
        };
    }
}
