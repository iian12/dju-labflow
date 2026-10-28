package com.dju.labflow.auth.infrastructure.security;

import com.dju.labflow.user.domain.Role;
import com.dju.labflow.user.domain.UserId;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;

/** 일반 사용자 요청의 인증 주체. 역할을 Spring Security 권한 문자열로 변환한다. */
public record AppPrincipal(UserId userId, Role role) {
    public AppPrincipal {
        if (userId == null) {
            throw new IllegalArgumentException("User ID must not be null");
        }

        if (role == null) {
            throw new IllegalArgumentException("Role must not be null");
        }
    }

    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(() -> "ROLE_" + role.name());
    }
}
