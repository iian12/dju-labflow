package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dju.labflow.auth.application.PersistentRequestLimiter;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
        properties = {"labflow.auth.login.ip-limit=5", "labflow.auth.login.account-ip-limit=2"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthenticationRateLimitTests {
    @Autowired MockMvc mvc;
    @Autowired PersistentRequestLimiter limiter;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean Clock clock;
    private static final Instant NOW = Instant.parse("2026-11-25T00:02:00Z");

    @BeforeEach
    void time() {
        when(clock.instant()).thenReturn(NOW);
    }

    @AfterEach
    void cleanup() {
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status ->
                                em.createNativeQuery("delete from auth_request_bucket")
                                        .executeUpdate());
    }

    @Test
    void repeatedLoginIsLimitedByCanonicalAccountAndTrustedClientIp() throws Exception {
        login("127.0.0.1", "203.0.113.1", "missing@example.com", 401);
        login("127.0.0.1", "203.0.113.1", "MISSING@example.com", 401);
        login("127.0.0.1", "1.2.3.4, 203.0.113.1", "missing@example.com", 429);
        login("127.0.0.1", "203.0.113.2", "missing@example.com", 401);
        login("198.51.100.1", "1.1.1.1", "other@example.com", 401);
        login("198.51.100.1", "2.2.2.2", "other@example.com", 401);
        login("198.51.100.1", "3.3.3.3", "other@example.com", 429);
    }

    @Test
    void ipBudgetCannotBeBypassedByChangingAccountsAndExpires() throws Exception {
        for (int index = 0; index < 5; index++)
            login("198.51.100.2", "", "user" + index + "@example.com", 401);
        login("198.51.100.2", "", "new@example.com", 429);
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        login("198.51.100.2", "", "new@example.com", 401);
    }

    @Test
    void mailBudgetsAreSeparateForDifferentClientsBehindTheGateway() throws Exception {
        for (int index = 0; index < 11; index++)
            mvc.perform(
                            post("/api/v1/auth/email-verifications/resend")
                                    .with(csrf())
                                    .with(
                                            request -> {
                                                request.setRemoteAddr("127.0.0.1");
                                                return request;
                                            })
                                    .header("X-Forwarded-For", "203.0.113.3")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"email\":\"unknown@example.com\"}"))
                    .andExpect(status().is(index < 10 ? 202 : 429));
        mvc.perform(
                        post("/api/v1/auth/email-verifications/resend")
                                .with(csrf())
                                .with(
                                        request -> {
                                            request.setRemoteAddr("127.0.0.1");
                                            return request;
                                        })
                                .header("X-Forwarded-For", "203.0.113.4")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"unknown@example.com\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void concurrentRequestsAndIndependentInstancesShareOneAtomicBudget() throws Exception {
        String identity = UUID.randomUUID().toString();
        AtomicInteger allowed = new AtomicInteger(), rejected = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> calls = new ArrayList<>();
            for (int index = 0; index < 16; index++)
                calls.add(
                        pool.submit(
                                () -> {
                                    try {
                                        limiter.check(
                                                "concurrent", identity, 3, Duration.ofMinutes(15));
                                        allowed.incrementAndGet();
                                    } catch (ResponseStatusException limited) {
                                        assertThat(limited.getStatusCode().value()).isEqualTo(429);
                                        rejected.incrementAndGet();
                                    }
                                }));
            for (Future<?> call : calls) call.get(10, TimeUnit.SECONDS);
        }
        assertThat(allowed).hasValue(3);
        assertThat(rejected).hasValue(13);
        PersistentRequestLimiter second = new PersistentRequestLimiter(em, clock);
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(transactions)
                                        .executeWithoutResult(
                                                status ->
                                                        second.check(
                                                                "concurrent",
                                                                identity,
                                                                3,
                                                                Duration.ofMinutes(15))))
                .isInstanceOf(ResponseStatusException.class);
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            List<?> keys =
                                    em.createNativeQuery(
                                                    "select bucket_key from auth_request_bucket")
                                            .getResultList();
                            assertThat(keys)
                                    .allSatisfy(
                                            key ->
                                                    assertThat(key.toString())
                                                            .matches("[0-9a-f]{64}"));
                        });
    }

    private void login(String peer, String forwarded, String email, int expected) throws Exception {
        mvc.perform(
                        post("/api/v1/auth/login")
                                .with(
                                        request -> {
                                            request.setRemoteAddr(peer);
                                            return request;
                                        })
                                .header("X-Forwarded-For", forwarded)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\""
                                                + email
                                                + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().is(expected));
    }
}
