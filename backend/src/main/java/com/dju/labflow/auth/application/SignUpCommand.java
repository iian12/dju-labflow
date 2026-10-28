package com.dju.labflow.auth.application;

public record SignUpCommand(String email, String password, String confirmPassword, String name) {
}
