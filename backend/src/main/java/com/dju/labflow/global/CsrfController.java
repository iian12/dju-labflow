package com.dju.labflow.global;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 프런트엔드와 Swagger UI가 변경 요청 전에 CSRF 쿠키를 받을 수 있는 공개 진입점. */
@RestController
@Tag(name = "CSRF", description = "브라우저의 CSRF 초기화")
public class CsrfController {
    @GetMapping("/api/v1/auth/csrf")
    @Operation(
            summary = "CSRF 초기화",
            description = "XSRF-TOKEN 쿠키를 설정합니다. 변경 요청에는 쿠키 원문을 X-XSRF-TOKEN 헤더로 전송하세요.")
    @SecurityRequirements
    public void csrf(CsrfToken csrfToken) {
        // 토큰을 응답 본문으로 반환하지 않고 쿠키를 기준으로 클라이언트와 연동한다.
        csrfToken.getToken();
    }
}
