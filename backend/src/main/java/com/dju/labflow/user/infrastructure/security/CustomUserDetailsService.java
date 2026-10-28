package com.dju.labflow.user.infrastructure.security;

import com.dju.labflow.auth.infrastructure.security.LoginUserDetails;
import com.dju.labflow.user.domain.User;
import com.dju.labflow.user.domain.repository.UserRepository;
import com.dju.labflow.user.exception.UserNotFoundException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email).orElseThrow(UserNotFoundException::new);

        return LoginUserDetails.from(user);
    }
}
