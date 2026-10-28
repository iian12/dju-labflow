package com.dju.labflow.auth.application;

import com.dju.labflow.auth.application.token.IssuedRefreshToken;
import com.dju.labflow.auth.application.token.RefreshTokenGenerator;
import com.dju.labflow.auth.domain.RefreshToken;
import com.dju.labflow.auth.domain.RefreshTokenHasher;
import com.dju.labflow.auth.domain.RefreshTokenId;
import com.dju.labflow.auth.domain.RefreshTokenRepository;
import com.dju.labflow.auth.infrastructure.jwt.AccessTokenProvider;
import com.dju.labflow.auth.infrastructure.security.LoginUserDetails;
import com.dju.labflow.global.id.IdGenerator;
import com.dju.labflow.user.domain.User;
import com.dju.labflow.user.domain.UserId;
import com.dju.labflow.user.domain.repository.UserRepository;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final AccessTokenProvider accessTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final RefreshTokenHasher refreshTokenHasher;
    private final PasswordEncoder passwordEncoder;

    public AuthService(
            AuthenticationManager authenticationManager,
            UserRepository userRepository,
            RefreshTokenGenerator refreshTokenGenerator,
            AccessTokenProvider accessTokenProvider,
            RefreshTokenRepository refreshTokenRepository,
            IdGenerator idGenerator,
            Clock clock,
            RefreshTokenHasher refreshTokenHasher,
            PasswordEncoder passwordEncoder) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.refreshTokenGenerator = refreshTokenGenerator;
        this.accessTokenProvider = accessTokenProvider;
        this.refreshTokenRepository = refreshTokenRepository;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.refreshTokenHasher = refreshTokenHasher;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserId signUp(SignUpCommand command) {
        if (!command.password().equals(command.confirmPassword())) {
            throw new IllegalArgumentException("Passwords do not match.");
        }

        if (userRepository.existsByEmail(command.email())) {
            throw new IllegalArgumentException("Email already exists.");
        }

        UserId userId = UserId.of(idGenerator.nextId());

        // Instant now = clock.instant();

        User user =
                User.createPendingUser(
                        userId,
                        command.email(),
                        passwordEncoder.encode(command.password()),
                        command.name());

        User savedUser = userRepository.save(user);
        return savedUser.getId();
    }

    @Transactional
    public LoginResult login(LoginCommand command) {
        Authentication authentication =
                authenticationManager.authenticate(
                        new UsernamePasswordAuthenticationToken(
                                command.email(), command.password()));

        Object principal = authentication.getPrincipal();

        if (!(principal instanceof LoginUserDetails userDetails)) {
            throw new IllegalStateException(
                    "Unsupported authentication principal: " + principal.getClass().getName());
        }

        String accessToken =
                accessTokenProvider.createAccessToken(userDetails.userId(), userDetails.role());
        IssuedRefreshToken issuedRefreshToken = refreshTokenGenerator.generate();

        Instant now = clock.instant();

        RefreshToken refreshToken =
                RefreshToken.create(
                        RefreshTokenId.of(idGenerator.nextId()),
                        userDetails.userId(),
                        refreshTokenHasher.hash(issuedRefreshToken.rawToken()),
                        issuedRefreshToken.expiresAt(),
                        now);

        refreshTokenRepository.save(refreshToken);

        return new LoginResult(
                accessToken, issuedRefreshToken.rawToken(), issuedRefreshToken.expiresAt());
    }
}
