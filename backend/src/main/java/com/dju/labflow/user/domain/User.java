package com.dju.labflow.user.domain;

import java.util.Objects;
import com.dju.labflow.global.validation.InputValidation;

import com.dju.labflow.user.exception.NotAdminException;
import lombok.Getter;

/** 사용자의 프로필, 비밀번호 해시, 역할과 계정 상태 전이를 관리하는 도메인. */
@Getter
public class User {

    private final UserId id;
    private final String email;
    private String encodedPassword;
    private String name;
    private Role role;
    private AccountStatus accountStatus;

    private User(
            UserId id,
            String email,
            String encodedPassword,
            String name,
            Role role,
            AccountStatus accountStatus) {
        this.id = Objects.requireNonNull(id);
        this.email = InputValidation.text(email, "Email", 254);
        this.encodedPassword = InputValidation.text(encodedPassword, "Encoded password", 255);
        this.name = InputValidation.text(name, "Name", 100);
        this.role = Objects.requireNonNull(role);
        this.accountStatus = Objects.requireNonNull(accountStatus);
    }

    public static User createPendingUser(
            UserId id, String email, String encodedPassword, String name) {
        return new User(
                id, email, encodedPassword, name, Role.USER, AccountStatus.PENDING_VERIFICATION);
    }

    public static User restore(
            UserId id,
            String email,
            String encodedPassword,
            String name,
            Role role,
            AccountStatus accountStatus) {
        return new User(id, email, encodedPassword, name, role, accountStatus);
    }

    public void changeName(String name) {
        this.name = InputValidation.text(name, "Name", 100);
    }

    public void changePassword(String encodedPassword) {
        this.encodedPassword = InputValidation.text(encodedPassword, "Encoded password", 255);
    }

    public void activate() {
        if (accountStatus != AccountStatus.PENDING_VERIFICATION) {
            throw new IllegalStateException("Invalid account status");
        }
        this.accountStatus = AccountStatus.ACTIVE;
    }

    public void promoteToAdmin() {
        if (role != Role.USER) {
            throw new IllegalStateException("User is already an admin");
        }
        this.role = Role.ADMIN;
    }

    public void revokeAdmin() {
        if (this.role != Role.ADMIN) {
            throw new NotAdminException();
        }

        this.role = Role.USER;
    }

    public void block() {
        this.accountStatus = AccountStatus.BLOCKED;
    }
}
