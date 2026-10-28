package com.dju.labflow.auth.application;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 회원가입 입력 규칙. 서비스에서도 검증하므로 HTTP 외의 호출에도 같은 제약이 적용된다. */
public record SignUpCommand(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(min = 8, max = 72) String confirmPassword,
        @NotBlank @Size(max = 100) String name) {
}
