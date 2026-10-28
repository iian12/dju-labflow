package com.dju.labflow.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 실제 컨트롤러에서 OpenAPI 명세를 만들고 쿠키 인증과 CSRF 요구사항을 설명한다. */
@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI labflowOpenApi() {
        return new OpenAPI()
                .info(new Info().title("LabFlow Backend API").version("v1")
                        .description("구현된 HTTP API만 표시합니다. 회원가입/로그인/프로젝트 생성은 현재 서비스 계층만 구현되어 있습니다. "
                                + "ACTIVE 계정만 인증할 수 있으며 계정 차단 및 권한 변경은 다음 요청부터 반영됩니다. "
                                + "쿠키 인증 요청은 access_token 쿠키를 사용하고, 변경 요청에는 X-XSRF-TOKEN 헤더가 필요합니다. "
                                + "Swagger UI에서는 먼저 CSRF 조회 API를 실행하면 CSRF 쿠키를 읽어 헤더에 전달합니다."))
                .components(new Components()
                        .addSecuritySchemes("cookieAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.COOKIE).name("access_token")
                                .description("브라우저가 전송하는 HttpOnly JWT 쿠키. Swagger Authorize에서 HttpOnly 쿠키를 직접 설정할 수 없습니다."))
                        .addSecuritySchemes("csrfToken", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-XSRF-TOKEN")
                                .description("XSRF-TOKEN 쿠키 값. 변경 요청의 CSRF 검증에 사용합니다."))
                        .addSecuritySchemes("projectApiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer")
                                .description("향후 /api/v1/sdk/** API에서 사용하는 프로젝트 API 키. 사용자 JWT와 구분합니다.")))
                // 한 객체에 두 항목을 넣어 쿠키 인증 AND CSRF 조건을 표현한다.
                .addSecurityItem(new SecurityRequirement().addList("cookieAuth").addList("csrfToken"));
    }
}
