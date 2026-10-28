package com.dju.labflow.auth.domain;

import java.time.Instant;

/** 토큰 회전 결과. 응답 계층이 새 인증 쿠키를 작성할 때 사용한다. */
public record TokenRefreshResult(
        String accessToken, String refreshToken, Instant refreshTokenExpiresAt) {}
