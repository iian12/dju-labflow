package com.dju.labflow.auth.infrastructure.security;

import com.dju.labflow.user.domain.Role;
import com.dju.labflow.user.domain.User;
import com.dju.labflow.user.domain.UserId;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

public record LoginUserDetails(
        UserId userId,
        String email,
        String password,
        Role role
) implements UserDetails {

    public static LoginUserDetails from(User user) {
        return new LoginUserDetails(
                user.getId(),
                user.getEmail(),
                user.getEncodedPassword(),
                user.getRole()
        );
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(
                new SimpleGrantedAuthority(
                        "ROLE_" + role.name()
                )
        );
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public @Nullable String getPassword() {
        return password;
    }
}
