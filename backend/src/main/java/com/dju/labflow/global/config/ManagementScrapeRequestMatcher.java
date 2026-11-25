package com.dju.labflow.global.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

/** 별도 loopback 관리 포트의 지표 GET만 허용하며 전달 헤더는 신뢰하지 않는다. */
@Component
public final class ManagementScrapeRequestMatcher implements RequestMatcher {
    private static final Set<String> LOOPBACK =
            Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1");
    private final Environment environment;

    public ManagementScrapeRequestMatcher(Environment environment) {
        this.environment = environment;
    }

    @Override
    public boolean matches(HttpServletRequest request) {
        Integer configuredPort = environment.getProperty("management.server.port", Integer.class);
        if (configuredPort == null || configuredPort < 0) return false;
        int managementPort =
                environment.getProperty("local.management.port", Integer.class, configuredPort);
        int applicationPort =
                environment.getProperty(
                        "local.server.port",
                        Integer.class,
                        environment.getProperty("server.port", Integer.class, 8080));
        return managementPort > 0
                && managementPort != applicationPort
                && request.getLocalPort() == managementPort
                && LOOPBACK.contains(request.getLocalAddr())
                && LOOPBACK.contains(request.getRemoteAddr())
                && "GET".equals(request.getMethod())
                && (request.getContextPath() + "/actuator/prometheus")
                        .equals(request.getRequestURI());
    }
}
