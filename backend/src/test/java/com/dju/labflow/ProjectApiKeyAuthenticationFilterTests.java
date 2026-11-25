package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dju.labflow.global.handler.CustomAuthenticationEntryPoint;
import com.dju.labflow.project.key.application.ProjectApiKeyAuthenticationService;
import com.dju.labflow.project.key.domain.*;
import com.dju.labflow.project.key.exception.*;
import com.dju.labflow.project.key.infrastructure.security.ProjectApiKeyAuthenticationFilter;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.user.domain.UserId;
import jakarta.servlet.FilterChain;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

/** 필터 단계의 일시 장애를 503으로 구분하고 SDK 처리 본문은 재실행하지 않는다. */
class ProjectApiKeyAuthenticationFilterTests {
    private final ProjectApiKeyAuthenticationService service =
            mock(ProjectApiKeyAuthenticationService.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final ProjectApiKeyAuthenticationFilter filter =
            new ProjectApiKeyAuthenticationFilter(
                    service, new CustomAuthenticationEntryPoint(json), json);
    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void exhaustedAuthenticationReturns503WithoutExecutingSdkOrLeakingDetails() throws Exception {
        when(service.authenticate("test-only-key"))
                .thenThrow(
                        new ProjectApiKeyAuthenticationUnavailableException(
                                new SQLException("private SQL", "HY000", 1020)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(), response, chain);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getContentAsString())
                .contains("AUTHENTICATION_TEMPORARILY_UNAVAILABLE")
                .doesNotContain("private SQL", "test-only-key");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(chain);
    }

    @Test
    void invalidKeyStillReturns401() throws Exception {
        when(service.authenticate(anyString()))
                .thenThrow(new InvalidProjectApiKeyException("invalid"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(), response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void doesNotTreatDownstreamExceptionsAsAuthenticationFailuresOrReplaySdk() throws Exception {
        when(service.authenticate(anyString()))
                .thenReturn(
                        new ProjectApiKeyPrincipal(
                                ProjectApiKeyId.of(1L), ProjectId.of(2L), UserId.of(3L)));
        RuntimeException downstream = new IllegalStateException("SDK application failure");
        doThrow(downstream).when(chain).doFilter(any(), any());
        assertThatThrownBy(() -> filter.doFilter(request(), new MockHttpServletResponse(), chain))
                .isSameAs(downstream);
        verify(service).authenticate("test-only-key");
        verify(chain).doFilter(any(), any());
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sdk/runs");
        request.addHeader("Authorization", "Bearer test-only-key");
        return request;
    }
}
