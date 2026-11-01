package com.dju.labflow.auth.presentation;

import com.dju.labflow.auth.application.LoginCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

/** 사용자 로그인 요청. 비밀번호는 정규화하거나 로그로 출력하지 않는다. */
public record LoginRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank
                @Size(max = 72)
                @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
                String password) {
    public LoginCommand toCommand() {
        return new LoginCommand(email, password);
    }

    @Override
    public String toString() {
        return "LoginRequest[credentials=<redacted>]";
    }
}
