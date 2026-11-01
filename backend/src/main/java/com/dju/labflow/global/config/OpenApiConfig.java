package com.dju.labflow.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 실제 컨트롤러의 OpenAPI 명세와 쿠키/CSRF, SDK Bearer 인증 요구사항을 설명한다. */
@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI labflowOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("LabFlow Backend API")
                                .version("v1")
                                .description(
                                        "회원가입, 이메일 코드 인증, 로그인/토큰 갱신/로그아웃, 개인 프로젝트 관리와 SDK 실행 API를 제공합니다. "
                                                + "ACTIVE 계정만 인증할 수 있으며 계정 차단 및 권한 변경은 다음 요청부터 반영됩니다. "
                                                + "쿠키 인증 요청은 access_token 쿠키를 사용하고, 변경 요청에는 X-XSRF-TOKEN 헤더가 필요합니다. "
                                                + "Swagger UI에서는 먼저 CSRF 조회 API를 실행하면 CSRF 쿠키를 읽어 헤더에 전달합니다. "
                                                + "SDK API는 프로젝트 Bearer API 키만 사용하며 쿠키 인증과 CSRF를 요구하지 않습니다."))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "cookieAuth",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.APIKEY)
                                                .in(SecurityScheme.In.COOKIE)
                                                .name("access_token")
                                                .description(
                                                        "브라우저가 전송하는 HttpOnly JWT 쿠키. Swagger Authorize에서 HttpOnly 쿠키를 직접 설정할 수 없습니다."))
                                .addSecuritySchemes(
                                        "csrfToken",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.APIKEY)
                                                .in(SecurityScheme.In.HEADER)
                                                .name("X-XSRF-TOKEN")
                                                .description(
                                                        "XSRF-TOKEN 쿠키 값. 변경 요청의 CSRF 검증에 사용합니다."))
                                .addSecuritySchemes(
                                        "refreshTokenCookie",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.APIKEY)
                                                .in(SecurityScheme.In.COOKIE)
                                                .name("refresh_token")
                                                .description(
                                                        "로그인 시 발급된 HttpOnly refresh 쿠키. /api/v1/auth 경로에서 브라우저가 자동 전송합니다."))
                                .addSecuritySchemes(
                                        "projectApiKey",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .description(
                                                        "/api/v1/sdk/** API에서 사용하는 프로젝트 API 키. Authorize에 원문 키를 입력하면 Bearer 헤더로 전송합니다. 사용자 JWT/CSRF는 사용하지 않습니다.")))
                // 한 객체에 두 항목을 넣어 쿠키 인증 AND CSRF 조건을 표현한다.
                .addSecurityItem(
                        new SecurityRequirement().addList("cookieAuth").addList("csrfToken"));
    }

    /** 여러 애너테이션의 OR 표현 대신 refresh 쿠키 AND CSRF 요구사항을 명시한다. */
    @Bean
    public OpenApiCustomizer refreshSecurityCustomizer() {
        return openApi -> {
            var path = openApi.getPaths().get("/api/v1/auth/refresh");
            if (path != null && path.getPost() != null) {
                path.getPost()
                        .setSecurity(
                                List.of(
                                        new SecurityRequirement()
                                                .addList("refreshTokenCookie")
                                                .addList("csrfToken")));
            }
        };
    }
}
