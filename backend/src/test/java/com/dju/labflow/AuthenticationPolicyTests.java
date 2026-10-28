package com.dju.labflow;

import com.dju.labflow.auth.infrastructure.jwt.AccessTokenProvider;
import com.dju.labflow.auth.infrastructure.jwt.JwtProperties;
import com.dju.labflow.auth.infrastructure.persistence.JwtAuthenticationFilter;
import com.dju.labflow.auth.infrastructure.security.AppPrincipal;
import com.dju.labflow.project.key.application.ProjectApiKeyAuthenticationService;
import com.dju.labflow.project.key.domain.*;
import com.dju.labflow.project.key.domain.repository.ProjectApiKeyRepository;
import com.dju.labflow.project.key.exception.InvalidProjectApiKeyException;
import com.dju.labflow.project.key.util.ProjectApiKeyHasher;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.user.domain.*;
import com.dju.labflow.user.domain.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 토큰에 남은 이전 권한, 계정 차단, 필터 예외 경계를 외부 DB 없이 검증한다. */
class AuthenticationPolicyTests {
    private final UserRepository users = mock(UserRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final AccessTokenProvider tokens = new AccessTokenProvider(
            new JwtProperties("test-only-key-with-at-least-32-bytes", Duration.ofMinutes(15), Duration.ofDays(7)), clock);

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void jwtUsesCurrentRoleInsteadOfPreviouslyIssuedAdminClaim() throws Exception {
        User current = user(AccountStatus.ACTIVE);
        when(users.findById(current.getId())).thenReturn(Optional.of(current));
        var filter = new JwtAuthenticationFilter(tokens, users);
        var request = requestWithToken(tokens.createAccessToken(current.getId(), Role.ADMIN));
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            AppPrincipal principal = (AppPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            assertThat(principal.role()).isEqualTo(Role.USER);
        });
        verify(users).findById(current.getId());
    }

    @Test
    void jwtDoesNotRetryDownstreamRequestWhenApplicationThrowsIllegalArgument() {
        User current = user(AccountStatus.ACTIVE);
        when(users.findById(current.getId())).thenReturn(Optional.of(current));
        var filter = new JwtAuthenticationFilter(tokens, users);
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> filter.doFilter(requestWithToken(tokens.createAccessToken(current.getId(), Role.USER)),
                new MockHttpServletResponse(), (req, res) -> {
                    calls.incrementAndGet();
                    throw new IllegalArgumentException("downstream error");
                })).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void malformedJwtContinuesOnceWithoutAuthentication() throws Exception {
        var calls = new AtomicInteger();
        new JwtAuthenticationFilter(tokens, users).doFilter(requestWithToken("invalid-jwt"),
                new MockHttpServletResponse(), (req, res) -> {
                    calls.incrementAndGet();
                    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
                });
        assertThat(calls.get()).isEqualTo(1);
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @EnumSource(AccountStatus.class)
    void sdkKeyRequiresAnActiveOwner(AccountStatus status) {
        var keys = mock(ProjectApiKeyRepository.class);
        var hasher = new ProjectApiKeyHasher();
        String raw = "test-api-key";
        ProjectApiKey key = ProjectApiKey.create(ProjectApiKeyId.of(5L), ProjectId.of(10L), UserId.of(1L),
                "test key", "lfp_test", hasher.hash(raw), ProjectApiKeyStatus.ACTIVE, clock.instant(), null, null);
        when(keys.findByKeyHash(hasher.hash(raw))).thenReturn(Optional.of(key));
        when(users.findById(UserId.of(1L))).thenReturn(Optional.of(user(status)));
        var service = new ProjectApiKeyAuthenticationService(keys, hasher, users);
        if (status == AccountStatus.ACTIVE) {
            assertThat(service.authenticate(raw).projectId()).isEqualTo(ProjectId.of(10L));
        } else {
            assertThatThrownBy(() -> service.authenticate(raw)).isInstanceOf(InvalidProjectApiKeyException.class);
        }
    }

    private MockHttpServletRequest requestWithToken(String token) {
        var request = new MockHttpServletRequest("GET", "/api/private");
        request.setCookies(new Cookie("access_token", token));
        return request;
    }

    private User user(AccountStatus status) {
        return User.restore(UserId.of(1L), "test@example.test", "encoded-password", "test", Role.USER, status);
    }
}
