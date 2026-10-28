package com.dju.labflow.auth.application.token;

import java.time.Instant;

public record IssuedRefreshToken(String rawToken, Instant expiresAt) {}
