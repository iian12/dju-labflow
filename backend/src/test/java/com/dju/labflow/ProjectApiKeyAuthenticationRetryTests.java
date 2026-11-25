package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.dju.labflow.project.key.application.*;
import com.dju.labflow.project.key.domain.*;
import com.dju.labflow.project.key.exception.*;
import com.dju.labflow.project.key.util.ProjectApiKeyHasher;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.user.domain.UserId;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 재시도 범위·횟수와 인증 거부의 구분을 검증한다. */
class ProjectApiKeyAuthenticationRetryTests {
    private final ProjectApiKeyAuthenticationAttempt attempt =
            mock(ProjectApiKeyAuthenticationAttempt.class);
    private final ProjectApiKeyHasher hasher = new ProjectApiKeyHasher();
    private final ProjectApiKeyAuthenticationService authentication =
            new ProjectApiKeyAuthenticationService(attempt, hasher);
    private final ProjectApiKeyPrincipal principal =
            new ProjectApiKeyPrincipal(ProjectApiKeyId.of(1L), ProjectId.of(2L), UserId.of(3L));

    @ParameterizedTest
    @ValueSource(ints = {1020, 1213, 1205})
    void retriesOnlyKnownSqlConflictsAndPassesOnlyTheHash(int code) {
        String hash = hasher.hash("test-only-key");
        when(attempt.authenticate(hash))
                .thenThrow(new RuntimeException(new SQLException("test conflict", "HY000", code)))
                .thenReturn(principal);
        assertThat(authentication.authenticate("test-only-key")).isEqualTo(principal);
        verify(attempt, times(2)).authenticate(hash);
        verifyNoMoreInteractions(attempt);
    }

    @Test
    void retriesSerializationStateAndSqlNextExceptions() {
        SQLException wrapper = new SQLException("wrapper", "HY000", 0);
        wrapper.setNextException(new SQLException("serialization", "40001", 0));
        when(attempt.authenticate(anyString()))
                .thenThrow(new RuntimeException(wrapper))
                .thenReturn(principal);
        assertThat(authentication.authenticate("test-only-key")).isEqualTo(principal);
        verify(attempt, times(2)).authenticate(anyString());
    }

    @Test
    void stopsAfterThreeAttemptsAndKeepsDatabaseDetailsOutOfThePublicMessage() {
        RuntimeException conflict =
                new RuntimeException(new SQLException("private SQL detail", "HY000", 1020));
        when(attempt.authenticate(anyString())).thenThrow(conflict);
        assertThatThrownBy(() -> authentication.authenticate("test-only-key"))
                .isInstanceOf(ProjectApiKeyAuthenticationUnavailableException.class)
                .hasCause(conflict)
                .hasMessageNotContaining("private SQL detail")
                .hasMessageNotContaining("test-only-key");
        verify(attempt, times(3)).authenticate(anyString());
    }

    @Test
    void doesNotRetryInvalidKeysOrUnexpectedFailures() {
        when(attempt.authenticate(anyString()))
                .thenThrow(new InvalidProjectApiKeyException("revoked"));
        assertThatThrownBy(() -> authentication.authenticate("test-only-key"))
                .isInstanceOf(InvalidProjectApiKeyException.class);
        verify(attempt).authenticate(anyString());
        reset(attempt);
        RuntimeException unexpected =
                new RuntimeException(new SQLException("syntax", "42000", 1064));
        when(attempt.authenticate(anyString())).thenThrow(unexpected);
        assertThatThrownBy(() -> authentication.authenticate("test-only-key")).isSameAs(unexpected);
        verify(attempt).authenticate(anyString());
    }

    @Test
    void stopsRetryingWhenFreshStateRejectsTheKey() {
        when(attempt.authenticate(anyString()))
                .thenThrow(new RuntimeException(new SQLException("conflict", "HY000", 1020)))
                .thenThrow(new InvalidProjectApiKeyException("revoked between attempts"));
        assertThatThrownBy(() -> authentication.authenticate("test-only-key"))
                .isInstanceOf(InvalidProjectApiKeyException.class);
        verify(attempt, times(2)).authenticate(anyString());
    }

    @Test
    void interruptedBackoffRestoresTheInterruptFlag() {
        when(attempt.authenticate(anyString()))
                .thenThrow(new RuntimeException(new SQLException("conflict", "HY000", 1020)));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> authentication.authenticate("test-only-key"))
                    .isInstanceOf(ProjectApiKeyAuthenticationUnavailableException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(attempt).authenticate(anyString());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void blankKeysNeverReachTheDatabaseAttempt() {
        assertThatThrownBy(() -> authentication.authenticate(" "))
                .isInstanceOf(InvalidProjectApiKeyException.class);
        verifyNoInteractions(attempt);
    }
}
