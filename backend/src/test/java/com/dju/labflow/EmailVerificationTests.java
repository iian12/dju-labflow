package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dju.labflow.auth.application.AuthService;
import com.dju.labflow.auth.application.SignUpCommand;
import com.dju.labflow.auth.application.verification.EmailVerificationCodeGenerator;
import com.dju.labflow.auth.application.verification.EmailVerificationService;
import com.dju.labflow.auth.application.verification.VerificationEmailSender;
import com.dju.labflow.auth.domain.EmailVerificationRepository;
import com.dju.labflow.auth.domain.EmailVerificationToken;
import com.dju.labflow.auth.domain.exception.InvalidVerificationCodeException;
import com.dju.labflow.auth.infrastructure.persistence.EmailVerificationJpaRepository;
import com.dju.labflow.auth.infrastructure.persistence.EmailVerificationMapper;
import com.dju.labflow.user.domain.AccountStatus;
import com.dju.labflow.user.domain.repository.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 트랜잭션 커밋을 사용하여 실패 횟수와 메일 발송 시점을 검증한다. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class EmailVerificationTests {
    @Autowired AuthService auth;
    @Autowired EmailVerificationService service;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EmailVerificationRepository verificationRepository;

    @Autowired EmailVerificationJpaRepository verificationJpaRepository;

    @MockitoBean VerificationEmailSender sender;
    @MockitoBean EmailVerificationCodeGenerator generator;

    @BeforeEach
    void codes() {
        when(generator.generate()).thenReturn("A2B3C4", "D5E6F7");
        when(generator.normalize(anyString()))
                .thenAnswer(
                        invocation ->
                                new EmailVerificationCodeGenerator()
                                        .normalize(invocation.getArgument(0)));
    }

    @Test
    void repositoryPreservesInsertOnlySemanticsAndAssignedIdLifecycle() {
        String email = signUp();
        Long userId = users.findByEmail(email).orElseThrow().getId().value();
        var transactions = new TransactionTemplate(transactionManager);
        transactions.executeWithoutResult(
                status -> {
                    var entity = verificationJpaRepository.findById(userId).orElseThrow();
                    assertThat(entity.isNew()).isFalse();
                    var token = verificationRepository.findForUpdate(userId).orElseThrow();
                    var mapped = EmailVerificationMapper.toEntity(token);
                    assertThat(mapped.isNew()).isTrue();
                    assertThat(EmailVerificationMapper.toDomain(mapped))
                            .usingRecursiveComparison()
                            .isEqualTo(token);
                });
        assertThatThrownBy(
                        () ->
                                transactions.executeWithoutResult(
                                        status -> {
                                            verificationRepository.save(
                                                    EmailVerificationToken.issue(
                                                            userId, "replacement", Instant.now()));
                                            verificationJpaRepository.flush();
                                        }))
                .isInstanceOf(DataIntegrityViolationException.class);
        service.verify(email, "A2B3C4");
    }

    @Test
    void lockingRepositoryRequiresServiceTransaction() {
        assertThatThrownBy(() -> verificationRepository.findForUpdate(1L))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private String signUp() {
        String email = UUID.randomUUID() + "@example.test";
        auth.signUp(new SignUpCommand(email, "password-123", "password-123", "tester"));
        return email;
    }

    @Test
    void signupSendsAfterCommitAndVerificationIsOneTime() {
        String email = signUp();
        verify(sender).send(email, "A2B3C4");
        assertThat(users.findByEmail(email).orElseThrow().getAccountStatus())
                .isEqualTo(AccountStatus.PENDING_VERIFICATION);
        var id = users.findByEmail(email).orElseThrow().getId().value();
        assertThat(
                        jdbc.queryForObject(
                                "select code_hash from email_verifications where user_id = ?",
                                String.class,
                                id))
                .hasSize(64)
                .doesNotContain("A2B3C4");
        service.verify(email, " a2b3c4 ");
        assertThat(users.findByEmail(email).orElseThrow().getAccountStatus())
                .isEqualTo(AccountStatus.ACTIVE);
        assertThatThrownBy(() -> service.verify(email, "A2B3C4"))
                .isInstanceOf(InvalidVerificationCodeException.class);
    }

    @Test
    void wrongAttemptsCommitAndFifthFailureLocksCode() {
        String email = signUp();
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.verify(email, "Z9Z9Z9"))
                    .isInstanceOf(InvalidVerificationCodeException.class);
        }
        var id = users.findByEmail(email).orElseThrow().getId().value();
        assertThat(
                        jdbc.queryForObject(
                                "select failures from email_verifications where user_id = ?",
                                Integer.class,
                                id))
                .isEqualTo(5);
        assertThatThrownBy(() -> service.verify(email, "A2B3C4"))
                .isInstanceOf(InvalidVerificationCodeException.class);
    }

    @Test
    void resendIsThrottledAndReplacesOldCode() {
        String email = signUp();
        service.resend(email);
        verify(sender, times(1)).send(eq(email), anyString());
        var id = users.findByEmail(email).orElseThrow().getId().value();
        jdbc.update(
                "update email_verifications set sent_at = ? where user_id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(61)),
                id);
        service.resend(email);
        verify(sender).send(email, "D5E6F7");
        assertThatThrownBy(() -> service.verify(email, "A2B3C4"))
                .isInstanceOf(InvalidVerificationCodeException.class);
        service.verify(email, "D5E6F7");
    }

    @Test
    void expiredAndBlockedAccountsCannotVerify() {
        String email = signUp();
        var user = users.findByEmail(email).orElseThrow();
        jdbc.update(
                "update email_verifications set expires_at = ? where user_id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
                user.getId().value());
        assertThatThrownBy(() -> service.verify(email, "A2B3C4"))
                .isInstanceOf(InvalidVerificationCodeException.class);
        String blockedEmail = signUp();
        var blocked = users.findByEmail(blockedEmail).orElseThrow();
        blocked.block();
        users.update(blocked);
        assertThatThrownBy(() -> service.verify(blockedEmail, "D5E6F7"))
                .isInstanceOf(InvalidVerificationCodeException.class);
        assertThat(users.findByEmail(blockedEmail).orElseThrow().getAccountStatus())
                .isEqualTo(AccountStatus.BLOCKED);
    }

    @Test
    void rollbackDoesNotSendAndMailFailureDoesNotUndoSignup() {
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(transactionManager)
                                        .execute(
                                                status -> {
                                                    signUp();
                                                    throw new IllegalStateException("rollback");
                                                }))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(sender);
        doThrow(new IllegalStateException("SMTP unavailable"))
                .when(sender)
                .send(anyString(), anyString());
        String email = signUp();
        assertThat(users.findByEmail(email)).isPresent();
    }

    @Test
    void anonymousHttpFlowValidatesCsrfAndDoesNotExposeCodes() throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        mvc.perform(
                        post("/api/v1/auth/sign-up")
                                .contentType("application/json")
                                .content(
                                        "{\"email\":\""
                                                + email
                                                + "\",\"password\":\"password-123\",\"confirmPassword\":\"password-123\",\"name\":\"tester\"}"))
                .andExpect(status().isCreated());
        String body = "{\"email\":\"" + email + "\",\"code\":\"A2B3C4\"}";
        mvc.perform(
                        post("/api/v1/auth/email-verifications/verify")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/v1/auth/email-verifications/verify")
                                .with(csrf())
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isNoContent());
        mvc.perform(
                        post("/api/v1/auth/email-verifications/resend")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"email\":\"missing@example.test\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void codeGenerationAndDomainLimits() {
        var actual = new EmailVerificationCodeGenerator();
        for (int i = 0; i < 1000; i++) {
            assertThat(actual.generate()).matches("(?=.*[A-Z])(?=.*[2-9])[A-HJKMNP-Z2-9]{6}");
        }
        Instant now = Instant.parse("2026-10-29T00:00:00Z");
        var token = EmailVerificationToken.issue(1L, "hash", now);
        assertThat(token.usable(now.plusSeconds(600))).isFalse();
        for (int i = 1; i <= 4; i++) token.resend("hash", now.plusSeconds(60L * i));
        assertThat(token.canResend(now.plusSeconds(300))).isFalse();
        assertThat(token.canResend(now.plusSeconds(3600))).isTrue();
    }
}
