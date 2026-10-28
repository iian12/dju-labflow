package com.dju.labflow.user.infrastructure.persistence;

import com.dju.labflow.global.id.AssignedIdEntity;
import com.dju.labflow.user.domain.AccountStatus;
import com.dju.labflow.user.domain.Role;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "users")
public class UserEntity extends AssignedIdEntity {

    @Version
    private Long version;

    @Column(unique = true)
    private String email;

    @Column
    private String encodedPassword;

    @Column
    private String name;

    @Column
    @Enumerated(EnumType.STRING)
    private Role role;

    @Column
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
            String encodedPassword,
            String name,
            AccountStatus accountStatus
    ) {
        this.encodedPassword = encodedPassword;
        this.name = name;
        this.accountStatus = accountStatus;
    }
}
