package com.dju.labflow.user.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 이메일 기반 인증 조회와 중복 여부 확인을 제공하는 Spring Data 저장소. */
public interface UserJpaRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByEmail(String email);

    boolean existsByEmail(String email);
}
