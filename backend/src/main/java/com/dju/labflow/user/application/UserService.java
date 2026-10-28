package com.dju.labflow.user.application;

import com.dju.labflow.user.domain.User;
import com.dju.labflow.user.domain.UserId;
import com.dju.labflow.user.domain.repository.UserRepository;
import com.dju.labflow.user.exception.UserNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 이메일 인증을 마친 사용자의 계정 활성화 상태 전이를 저장한다. */
@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public void activateAccountStatus(UserId userId) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);

        user.activate();
        userRepository.update(user);
    }
}
