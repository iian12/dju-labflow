package com.dju.labflow.auth.presentation;

import com.dju.labflow.auth.application.AuthService;
import com.dju.labflow.auth.application.SignUpCommand;
import com.dju.labflow.auth.application.verification.EmailVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 회원가입과 코드 인증을 제공한다. 인증 코드는 응답에 포함하지 않는다. */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "회원가입 및 이메일 인증")
public class EmailVerificationController {
    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;
    private final VerificationRequestLimiter verificationRequestLimiter;

    public EmailVerificationController(
            AuthService authService,
            EmailVerificationService emailVerificationService,
            VerificationRequestLimiter verificationRequestLimiter) {
        this.authService = authService;
        this.emailVerificationService = emailVerificationService;
        this.verificationRequestLimiter = verificationRequestLimiter;
    }

    @PostMapping("/sign-up")
    @SecurityRequirements
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "회원가입 및 인증 코드 발송",
            description = "미인증 계정을 생성합니다. 메일 미수신 시 60초 후 재발송할 수 있습니다.")
    public void signUp(@Valid @RequestBody SignUpRequest body, HttpServletRequest request) {
        verificationRequestLimiter.check(request.getRemoteAddr(), false);
        authService.signUp(
                new SignUpCommand(
                        body.email(), body.password(), body.confirmPassword(), body.name()));
    }

    @PostMapping("/email-verifications/verify")
    @SecurityRequirements({@SecurityRequirement(name = "csrfToken")})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "인증 코드 확인",
            description = "코드는 10분간 유효하며 최대 5회 검증 실패 후 사용할 수 없습니다. CSRF 쿠키와 헤더가 필요합니다.")
    public void verify(@Valid @RequestBody VerifyRequest body, HttpServletRequest request) {
        verificationRequestLimiter.check(request.getRemoteAddr(), true);
        emailVerificationService.verify(body.email(), body.code());
    }

    @PostMapping("/email-verifications/resend")
    @SecurityRequirements({@SecurityRequirement(name = "csrfToken")})
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "인증 코드 재발송 요청",
            description = "60초 간격, 계정당 시간당 5회. 계정 존재/상태 및 제한 여부와 무관하게 202를 반환합니다. CSRF가 필요합니다.")
    public void resend(@Valid @RequestBody ResendRequest body, HttpServletRequest request) {
        verificationRequestLimiter.check(request.getRemoteAddr(), false);
        emailVerificationService.resend(body.email());
    }

    public record SignUpRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(min = 8, max = 72) String confirmPassword,
            @NotBlank @Size(max = 100) String name) {}

    public record VerifyRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 32) String code) {}

    public record ResendRequest(@NotBlank @Email @Size(max = 254) String email) {}
}
