package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;

import com.dju.labflow.auth.infrastructure.cookie.AuthCookieProperties;
import com.dju.labflow.auth.infrastructure.cookie.AuthCookieProvider;
import com.dju.labflow.auth.infrastructure.jwt.JwtProperties;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 브라우저가 삭제 쿠키를 기존 쿠키와 동일하게 인식하도록 경로와 정책을 확인한다. */
class AuthCookieProviderTests {
    @Test
    void productionCreationAndDeletionUseSameSecureSameSiteAndPaths() {
        Instant now = Instant.parse("2026-11-01T00:00:00Z");
        var provider =
                new AuthCookieProvider(
                        new JwtProperties(
                                "test-only-secret-at-least-32-bytes",
                                Duration.ofMinutes(15),
                                Duration.ofDays(7)),
                        new AuthCookieProperties(true, "None"),
                        Clock.fixed(now, ZoneOffset.UTC));
        var access = provider.createAccessTokenCookie("access");
        var refresh = provider.createRefreshTokenCookie("refresh", now.plusSeconds(60));
        var clearAccess = provider.deleteAccessTokenCookie();
        var clearRefresh = provider.deleteRefreshTokenCookie();
        for (var cookie : List.of(access, refresh, clearAccess, clearRefresh))
            assertThat(cookie.toString()).contains("HttpOnly", "Secure", "SameSite=None");
        assertThat(access.getPath()).isEqualTo(clearAccess.getPath()).isEqualTo("/");
        assertThat(refresh.getPath()).isEqualTo(clearRefresh.getPath()).isEqualTo("/api/v1/auth");
        assertThat(clearAccess.getMaxAge()).isZero();
        assertThat(clearRefresh.getMaxAge()).isZero();
        assertThat(provider.createRefreshTokenCookie("expired", now.minusSeconds(1)).getMaxAge())
                .isZero();
    }
}
