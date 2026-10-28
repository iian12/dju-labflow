package com.dju.labflow.auth.domain;

import com.dju.labflow.user.domain.Role;
import com.dju.labflow.user.domain.UserId;

/** 서명이 검증된 JWT에서 읽은 사용자 식별 정보. 현재 계정 권한은 요청 시 다시 조회한다. */
public record AccessTokenClaims(UserId userId, Role role) {}
