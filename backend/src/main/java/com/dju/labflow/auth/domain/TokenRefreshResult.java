package com.dju.labflow.auth.domain;

import java.time.Instant;

public record TokenRefreshResult(String accessToken, String refreshToken, Instant refreshTokenExpiresAt) {
}
