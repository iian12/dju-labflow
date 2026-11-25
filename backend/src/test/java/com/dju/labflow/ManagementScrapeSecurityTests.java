package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;

import com.dju.labflow.global.config.ManagementScrapeRequestMatcher;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

/** 지표의 익명 허용이 외부 peer, 일반 포트, 다른 경로와 메서드로 확대되지 않도록 한다. */
class ManagementScrapeSecurityTests {
    @Test
    void onlyLoopbackMetricsGetOnADedicatedPortIsAnonymous() {
        var matcher =
                new ManagementScrapeRequestMatcher(
                        new MockEnvironment().withProperty("management.server.port", "8081"));
        var request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.setLocalAddr("127.0.0.1");
        request.setRemoteAddr("127.0.0.1");
        request.setLocalPort(8081);
        assertThat(matcher.matches(request)).isTrue();
        request.setRemoteAddr("192.0.2.1");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        assertThat(matcher.matches(request)).isFalse();
        request.setRemoteAddr("127.0.0.1");
        request.setLocalPort(8080);
        assertThat(matcher.matches(request)).isFalse();
        request.setLocalPort(8081);
        request.setMethod("POST");
        assertThat(matcher.matches(request)).isFalse();
        request.setMethod("GET");
        request.setRequestURI("/actuator/env");
        assertThat(matcher.matches(request)).isFalse();
    }

    @Test
    void unconfiguredOrSharedManagementPortsNeverAllowAnonymousMetrics() {
        var request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.setLocalAddr("127.0.0.1");
        request.setRemoteAddr("127.0.0.1");
        request.setLocalPort(8080);
        assertThat(new ManagementScrapeRequestMatcher(new MockEnvironment()).matches(request))
                .isFalse();
        assertThat(
                        new ManagementScrapeRequestMatcher(
                                        new MockEnvironment()
                                                .withProperty("management.server.port", "8080"))
                                .matches(request))
                .isFalse();
    }
}
