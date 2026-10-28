package com.dju.labflow.auth.infrastructure.security;

import com.dju.labflow.user.domain.AccountStatus;
import com.dju.labflow.user.domain.Role;
import com.dju.labflow.user.domain.User;
import com.dju.labflow.user.domain.UserId;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** 비밀번호 인증에 필요한 사용자 정보와 계정 상태를 Spring Security에 전달한다. */
public record LoginUserDetails(
        UserId userId, String email, String password, Role role, AccountStatus accountStatus)
        implements UserDetails {

    public static LoginUserDetails from(User user) {
        return new LoginUserDetails(
                user.getId(),
                user.getEmail(),
                user.getEncodedPassword(),
                user.getRole(),
                user.getAccountStatus());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
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
        return accountStatus != AccountStatus.BLOCKED;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        // 이메일 인증 전 계정과 차단 계정은 새 인증을 시작할 수 없다.
        return accountStatus == AccountStatus.ACTIVE;
    }

    @Override
    public @Nullable String getPassword() {
        return password;
    }
}
