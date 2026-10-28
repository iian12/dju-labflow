package com.dju.labflow.auth.application.token;

import java.time.Instant;

/** 새 리프레시 토큰 원문과 만료 시각. 원문은 응답용이며 DB에는 해시를 저장한다. */
public record IssuedRefreshToken(String rawToken, Instant expiresAt) {}
