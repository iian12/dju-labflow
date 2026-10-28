package com.dju.labflow.auth.application;

import java.time.Instant;

public record LoginResult(String accessToken, String refreshToken, Instant refreshTokenExpiresAt) {
}
