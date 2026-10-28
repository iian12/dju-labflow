package com.dju.labflow.auth.application;

/** 로그인에 필요한 이메일과 비밀번호. 빈 자격 증명은 서비스 호출 전에 거부한다. */
public record LoginCommand(String email, String password) {
    public LoginCommand {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email must not be null or blank");
        }

        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Password must not be null or blank");
        }
    }
}
