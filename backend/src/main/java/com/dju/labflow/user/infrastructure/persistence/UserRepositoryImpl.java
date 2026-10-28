package com.dju.labflow.user.infrastructure.persistence;

import com.dju.labflow.user.domain.User;
import com.dju.labflow.user.domain.UserId;
import com.dju.labflow.user.domain.repository.UserRepository;
import com.dju.labflow.user.exception.UserNotFoundException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 사용자 저장소의 JPA 어댑터. 수정 시 관리 중인 엔티티에 역할·상태·프로필을 반영한다. */
@Repository
public class UserRepositoryImpl implements UserRepository {

    private final UserJpaRepository jpaRepository;

    public UserRepositoryImpl(UserJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public User save(User user) {
        Objects.requireNonNull(user, "User must not be null");

        UserEntity savedEntity = jpaRepository.save(UserMapper.toEntity(user));

        return UserMapper.toDomain(savedEntity);
    }

    @Override
    @Transactional
    public void update(User user) {
        UserEntity entity =
                jpaRepository
                        .findById(user.getId().value())
                        .orElseThrow(UserNotFoundException::new);

        // 도메인에서 변경된 권한도 관리 중인 엔티티에 반영한다.
        entity.update(
                user.getEncodedPassword(), user.getName(), user.getRole(), user.getAccountStatus());
    }

    @Override
    public Optional<User> findById(UserId userId) {
        if (userId == null) return Optional.empty();

        return jpaRepository.findById(userId.value()).map(UserMapper::toDomain);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        Objects.requireNonNull(email, "Email must not be null");

        return jpaRepository.findByEmail(email).map(UserMapper::toDomain);
    }

    @Override
    public boolean existsByEmail(String email) {
        Objects.requireNonNull(email, "Email must not be null");

        return jpaRepository.existsByEmail(email);
    }
}
