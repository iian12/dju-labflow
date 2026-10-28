package com.dju.labflow.auth.infrastructure.security;

import com.dju.labflow.user.domain.Role;
import com.dju.labflow.user.domain.UserId;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.List;

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
