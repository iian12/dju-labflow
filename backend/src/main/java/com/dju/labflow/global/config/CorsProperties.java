package com.dju.labflow.global.config;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 자격 증명 쿠키를 허용할 정확한 출처 목록. 와일드카드와 경로가 있는 URL은 거부한다. */
@ConfigurationProperties("app.cors")
public record CorsProperties(List<String> allowedOrigins) {
    public CorsProperties {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException("At least one CORS origin is required");
        }
        allowedOrigins = List.copyOf(allowedOrigins);
        for (String origin : allowedOrigins) {
            URI uri = URI.create(origin);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException(
                        "CORS origins must be exact HTTP(S) origins without paths");
            }
        }
    }
}
