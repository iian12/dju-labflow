package com.dju.labflow.auth.application;

import jakarta.validation.constraints.*;

/** 로그인에 필요한 이메일과 비밀번호. 빈 자격 증명은 서비스 호출 전에 거부한다. */
public record LoginCommand(
        @NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 72) String password) {
    public LoginCommand {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email must not be null or blank");
        }

        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Password must not be null or blank");
        }
    }

    @Override
    public String toString() {
        return "LoginCommand[credentials=<redacted>]";
    }
}
