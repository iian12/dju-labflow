package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;

import com.dju.labflow.auth.infrastructure.cookie.AuthCookieProperties;
import com.dju.labflow.auth.infrastructure.jwt.JwtProperties;
import com.dju.labflow.global.config.CorsProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 실제 .env import를 이용해 환경별 설정 바인딩과 쿠키/CORS 정책을 검증한다. */
class EnvironmentConfigurationTests {
    @TempDir Path temporary;

    @Test
    void dotenvLoadsJwtCorsAndDatabaseProperties() throws Exception {
        Path dotenv = temporary.resolve(".env");
        Files.writeString(
                dotenv,
                """
                APP_PROFILE=local
                DB_URL=jdbc:mariadb://test-host:3306/testdb
                DB_USERNAME=test-user
                DB_PASSWORD=test-password
                JWT_SECRET_KEY=dotenv-test-only-key-at-least-32-bytes
                JWT_ACCESS_TOKEN_EXPIRATION=20m
                CORS_ALLOWED_ORIGINS=http://localhost:5173,https://frontend.example.test
                JWT_COOKIE_SECURE=false
                JWT_COOKIE_SAME_SITE=Lax
                """);
        runner(dotenv)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(
                                            context.getBean(JwtProperties.class)
                                                    .accessTokenExpiration()
                                                    .toMinutes())
                                    .isEqualTo(20);
                            assertThat(context.getBean(CorsProperties.class).allowedOrigins())
                                    .hasSize(2);
                            assertThat(context.getBean(AuthCookieProperties.class).secure())
                                    .isFalse();
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty("spring.datasource.url"))
                                    .isEqualTo("jdbc:mariadb://test-host:3306/testdb");
                        });
    }

    @Test
    void productionDefaultsToSecureCookiesAndDisabledDocumentation() throws Exception {
        Path dotenv = temporary.resolve(".env");
        Files.writeString(
                dotenv,
                """
                APP_PROFILE=prod
                DB_USERNAME=test-user
                DB_PASSWORD=test-password
                JWT_SECRET_KEY=dotenv-test-only-key-at-least-32-bytes
                CORS_ALLOWED_ORIGINS=https://frontend.example.test
                """);
        runner(dotenv)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(AuthCookieProperties.class).secure())
                                    .isTrue();
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty(
                                                            "springdoc.api-docs.enabled",
                                                            Boolean.class))
                                    .isFalse();
                        });
    }

    @Test
    void invalidCrossSiteCookieAndWildcardOriginsAreRejected() {
        assertThatThrownBy(() -> new AuthCookieProperties(false, "None"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CorsProperties(List.of("*")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CorsProperties(List.of("https://example.test/path")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ApplicationContextRunner runner(Path dotenv) {
        return new ApplicationContextRunner()
                .withPropertyValues(
                        "spring.config.location=classpath:/application.properties",
                        "DOTENV_LOCATION=" + dotenv.toUri() + "[.properties]")
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(PropertyBindings.class);
    }

    /** 테스트용 최소 구성: 실제 DB 연결이나 웹 서버 없이 설정만 바인딩한다. */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
        JwtProperties.class,
        CorsProperties.class,
        AuthCookieProperties.class
    })
    static class PropertyBindings {}
}
