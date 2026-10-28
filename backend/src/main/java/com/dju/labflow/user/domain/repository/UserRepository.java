package com.dju.labflow.user.domain.repository;

import com.dju.labflow.user.domain.User;
import com.dju.labflow.user.domain.UserId;
import java.util.Optional;

/** 사용자 신규 저장, 변경 반영, 이메일 및 ID 조회의 영속성 계약. */
public interface UserRepository {

    /** 신규 사용자 INSERT 전용. 기존 사용자의 변경은 update를 사용한다. */
    User save(User user);

    void update(User user);

    Optional<User> findById(UserId userId);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
