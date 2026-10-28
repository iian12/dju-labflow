package com.dju.labflow.user.domain.repository;

import com.dju.labflow.user.domain.User;
import com.dju.labflow.user.domain.UserId;

import java.util.Optional;

public interface UserRepository {

    User save(User user);

    void update(User user);

    Optional<User> findById(UserId userId);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
