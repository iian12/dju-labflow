package com.dju.labflow.user.domain;

import java.util.Objects;

import com.dju.labflow.user.exception.NotAdminException;
import lombok.Getter;

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
        this.email = Objects.requireNonNull(email);
        this.encodedPassword = Objects.requireNonNull(encodedPassword);
        this.name = Objects.requireNonNull(name);
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
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Invalid name");
        }
        this.name = name;
    }

    public void changePassword(String encodedPassword) {
        this.encodedPassword = encodedPassword;
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
