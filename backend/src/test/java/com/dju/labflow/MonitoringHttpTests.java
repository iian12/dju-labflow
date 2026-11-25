package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/** 실제 두 HTTP 포트에서 지표 수집과 일반 API 인증 경계가 분리되는지 확인한다. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "management.server.address=127.0.0.1",
            "management.endpoints.web.exposure.include=prometheus",
            "management.metrics.distribution.percentiles-histogram.http.server.requests=true"
        })
@ActiveProfiles("test")
class MonitoringHttpTests {
    @LocalServerPort int applicationPort;
    @Autowired Environment environment;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void metricsAreAvailableOnlyOnTheSeparateManagementListener() throws Exception {
        int managementPort =
                environment.getRequiredProperty("local.management.port", Integer.class);
        assertThat(managementPort).isNotEqualTo(applicationPort);
        assertThat(get(applicationPort, "/api/v1/auth/csrf").statusCode()).isEqualTo(200);
        HttpResponse<String> metrics = get(managementPort, "/actuator/prometheus");
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body())
                .contains("jvm_memory_used_bytes", "http_server_requests_seconds");
        assertThat(get(applicationPort, "/actuator/prometheus").statusCode()).isEqualTo(401);
        assertThat(get(applicationPort, "/api/v1/auth/me").statusCode()).isEqualTo(401);
        assertThat(get(managementPort, "/actuator/health").statusCode()).isEqualTo(401);
    }

    @Test
    void forwardedHeadersCannotOpenMetricsOnTheApplicationPort() throws Exception {
        int managementPort =
                environment.getRequiredProperty("local.management.port", Integer.class);
        HttpRequest request =
                HttpRequest.newBuilder(uri(applicationPort, "/actuator/prometheus"))
                        .header("Forwarded", "for=127.0.0.1;host=127.0.0.1:" + managementPort)
                        .header("X-Forwarded-Port", Integer.toString(managementPort))
                        .header("X-Forwarded-For", "127.0.0.1")
                        .GET()
                        .build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(401);
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(port, path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(int port, String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
