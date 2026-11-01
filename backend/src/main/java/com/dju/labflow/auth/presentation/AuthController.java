package com.dju.labflow.auth.presentation;

import com.dju.labflow.auth.application.AuthService;
import com.dju.labflow.auth.application.token.TokenLogoutService;
import com.dju.labflow.auth.application.token.TokenRefreshService;
import com.dju.labflow.auth.infrastructure.cookie.AuthCookieProvider;
import com.dju.labflow.global.SecurityErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.security.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/** 비밀번호 인증과 refresh 쿠키 회전/폐기를 연결한다. 토큰 원문은 Set-Cookie로만 전달한다. */
@RestController
@RequestMapping(value = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Authentication", description = "사용자 쿠키 로그인·토큰 갱신·로그아웃")
@ApiResponses({
    @ApiResponse(
            responseCode = "400",
            description = "잘못된 로그인 본문",
            content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class))),
    @ApiResponse(
            responseCode = "401",
            description = "잘못된 자격 증명·계정 상태·refresh 토큰",
            content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class))),
    @ApiResponse(
            responseCode = "403",
            description = "CSRF 검증 실패",
            content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class))),
    @ApiResponse(
            responseCode = "409",
            description = "토큰 저장 데이터 충돌",
            content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class)))
})
public class AuthController {
    private final AuthService authService;
    private final TokenRefreshService tokenRefreshService;
    private final TokenLogoutService tokenLogoutService;
    private final AuthCookieProvider authCookieProvider;

    public AuthController(
            AuthService authService,
            TokenRefreshService tokenRefreshService,
            TokenLogoutService tokenLogoutService,
            AuthCookieProvider authCookieProvider) {
        this.authService = authService;
        this.tokenRefreshService = tokenRefreshService;
        this.tokenLogoutService = tokenLogoutService;
        this.authCookieProvider = authCookieProvider;
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(
            summary = "로그인",
            description =
                    "ACTIVE 계정만 허용합니다. CSRF는 필요하지 않으며 access_token/refresh_token HttpOnly 쿠키를 발급합니다.")
    @ApiResponse(responseCode = "204", description = "인증 쿠키 발급", content = @Content)
    public ResponseEntity<Void> login(
            @Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        var result = authService.login(request.toCommand());
        return authenticated(
                result.accessToken(),
                result.refreshToken(),
                result.refreshTokenExpiresAt(),
                response);
    }

    @PostMapping("/refresh")
    @SecurityRequirements({@SecurityRequirement(name = "csrfToken")})
    @Operation(
            summary = "토큰 갱신",
            description =
                    "유효한 refresh_token 쿠키와 CSRF가 필요합니다. access JWT의 유효 여부와 무관하게 갱신하며 사용한 refresh 토큰은 폐기합니다.")
    @ApiResponse(responseCode = "204", description = "회전된 인증 쿠키 발급", content = @Content)
    public ResponseEntity<Void> refresh(
            @Parameter(hidden = true) @CookieValue(value = "refresh_token", required = false)
                    @Nullable String refreshToken,
            HttpServletResponse response) {
        var result = tokenRefreshService.refresh(refreshToken);
        return authenticated(
                result.accessToken(),
                result.refreshToken(),
                result.refreshTokenExpiresAt(),
                response);
    }

    @PostMapping("/logout")
    @SecurityRequirements({@SecurityRequirement(name = "csrfToken")})
    @Operation(
            summary = "로그아웃",
            description =
                    "CSRF가 필요합니다. 현재 refresh 토큰을 폐기하고 인증 쿠키를 삭제합니다. 토큰 누락·오류·만료·폐기는 모두 204입니다. 복사된 access JWT는 만료까지 유효합니다.")
    @ApiResponse(
            responseCode = "204",
            description = "현재 세션 쿠키 삭제 및 refresh 토큰 폐기",
            content = @Content)
    public ResponseEntity<Void> logout(
            @Parameter(hidden = true) @CookieValue(value = "refresh_token", required = false)
                    @Nullable String refreshToken,
            HttpServletResponse response) {
        tokenLogoutService.logout(refreshToken);
        SecurityContextHolder.clearContext();
        response.addHeader(
                HttpHeaders.SET_COOKIE, authCookieProvider.deleteAccessTokenCookie().toString());
        response.addHeader(
                HttpHeaders.SET_COOKIE, authCookieProvider.deleteRefreshTokenCookie().toString());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private ResponseEntity<Void> authenticated(
            String accessToken,
            String refreshToken,
            Instant expiresAt,
            HttpServletResponse response) {
        // Security 필터가 이미 발급한 XSRF-TOKEN 등의 Set-Cookie를 덮어쓰지 않고 추가한다.
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                authCookieProvider.createAccessTokenCookie(accessToken).toString());
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                authCookieProvider.createRefreshTokenCookie(refreshToken, expiresAt).toString());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
