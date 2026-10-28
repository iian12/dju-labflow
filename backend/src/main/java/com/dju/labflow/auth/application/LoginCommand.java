package com.dju.labflow.auth.application;

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
