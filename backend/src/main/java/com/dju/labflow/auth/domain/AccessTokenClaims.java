package com.dju.labflow.auth.domain;

import com.dju.labflow.user.domain.Role;
import com.dju.labflow.user.domain.UserId;

public record AccessTokenClaims(UserId userId, Role role) {
}
