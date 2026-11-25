package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dju.labflow.auth.infrastructure.jwt.AccessTokenProvider;
import com.dju.labflow.global.id.IdGenerator;
import com.dju.labflow.user.domain.*;
import com.dju.labflow.user.domain.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminApiHttpTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AccessTokenProvider tokens;
    @Autowired IdGenerator ids;

    @Test
    void anonymousAndBearerOnlyCannotAccessAdminTools() throws Exception {
        mvc.perform(get("/api/v1/admin/api-test")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/api-test").header("Authorization", "Bearer sdk-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void regularUsersAreDeniedAndSessionReturnsTheirCurrentRole() throws Exception {
        User user = user(Role.USER);
        mvc.perform(get("/api/v1/admin/api-test").cookie(cookie(user)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/auth/me").cookie(cookie(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void adminsReceiveCurrentControllerOperationsWithoutSecrets() throws Exception {
        User user = user(Role.ADMIN);
        String response =
                mvc.perform(get("/api/v1/admin/api-test").cookie(cookie(user)))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(response)
                .contains(
                        "/api/v1/sdk/runs/{runId}/heartbeat",
                        "/api/v1/projects/{projectId}/runs/{runId}/stop",
                        "/api/v1/admin/api-test");
        assertThat(response).doesNotContain("password", "rawApiKey", "keyHash");
        mvc.perform(get("/api/v1/auth/me").cookie(cookie(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void revokingAdminRejectsTheExistingAdminJwtOnTheNextRequest() throws Exception {
        User user = user(Role.ADMIN);
        Cookie jwt = cookie(user);
        user.revokeAdmin();
        users.update(user);
        mvc.perform(get("/api/v1/admin/api-test").cookie(jwt)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/auth/me").cookie(jwt)).andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void blockedAdminCannotAccessWithAnExistingJwt() throws Exception {
        User user = user(Role.ADMIN);
        Cookie jwt = cookie(user);
        user.block();
        users.update(user);
        mvc.perform(get("/api/v1/admin/api-test").cookie(jwt)).andExpect(status().isUnauthorized());
    }

    private User user(Role role) {
        UserId id = UserId.of(ids.nextId());
        User user =
                User.restore(
                        id,
                        "admin-test-" + id.value() + "@example.test",
                        "unused-test-hash",
                        "API tester",
                        role,
                        AccountStatus.ACTIVE);
        return users.save(user);
    }

    private Cookie cookie(User user) {
        return new Cookie("access_token", tokens.createAccessToken(user.getId(), user.getRole()));
    }
}
