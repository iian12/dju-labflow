package com.dju.labflow.auth.application;

import java.time.Instant;

/** 로그인 후 쿠키 작성에 사용할 토큰 원문과 리프레시 토큰 만료 시각. */
public record LoginResult(String accessToken, String refreshToken, Instant refreshTokenExpiresAt) {}
