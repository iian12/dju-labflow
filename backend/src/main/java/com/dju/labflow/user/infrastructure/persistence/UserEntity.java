package com.dju.labflow.user.infrastructure.persistence;

import com.dju.labflow.global.id.AssignedIdEntity;
import com.dju.labflow.user.domain.AccountStatus;
import com.dju.labflow.user.domain.Role;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 이메일 유일성과 사용자 버전을 관리하는 JPA 엔티티. 비밀번호는 해시만 저장한다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "users")
public class UserEntity extends AssignedIdEntity {

    @Version private Long version;

    @Column(unique = true, nullable = false, updatable = false, length = 254)
    private String email;

    @Column(nullable = false, length = 255)
    private String encodedPassword;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private Role role;

    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private AccountStatus accountStatus;

    @Builder
    public UserEntity(
            Long id,
            String email,
            String encodedPassword,
            String name,
            Role role,
            AccountStatus accountStatus) {
        this.id = id;
        this.email = email;
        this.encodedPassword = encodedPassword;
        this.name = name;
        this.role = role;
        this.accountStatus = accountStatus;
    }

    public void update(
            String encodedPassword, String name, Role role, AccountStatus accountStatus) {
        this.encodedPassword = encodedPassword;
        this.name = name;
        this.role = role;
        this.accountStatus = accountStatus;
    }
}
