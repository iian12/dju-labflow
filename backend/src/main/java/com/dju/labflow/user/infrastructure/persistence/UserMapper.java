package com.dju.labflow.user.infrastructure.persistence;

import com.dju.labflow.user.domain.User;
import com.dju.labflow.user.domain.UserId;

public class UserMapper {
    private UserMapper() {
        /* This utility class should not be instantiated */
    }

    // 도메인 -> 엔티티 변환용 팩토리 메서드
    public static UserEntity toEntity(User domain) {
        return UserEntity.builder()
                .id(domain.getId().value())
                .encodedPassword(domain.getEncodedPassword())
                .name(domain.getName())
                .email(domain.getEmail())
                .role(domain.getRole())
                .accountStatus(domain.getAccountStatus())
                .build();
    }

    // 엔티티 -> 도메인 변환용 팩토리 메서드
    public static User toDomain(UserEntity entity) {
        return User.restore(
                UserId.of(entity.getId()),
                entity.getEmail(),
                entity.getEncodedPassword(),
                entity.getName(),
                entity.getRole(),
                entity.getAccountStatus()
        );
    }
}
