package com.dju.labflow.auth.application.token;

import com.dju.labflow.auth.domain.*;
import com.dju.labflow.auth.domain.exception.InvalidRefreshTokenException;
import com.dju.labflow.auth.infrastructure.jwt.AccessTokenProvider;
import com.dju.labflow.global.id.IdGenerator;
import com.dju.labflow.user.domain.User;
import com.dju.labflow.user.domain.repository.UserRepository;
import com.dju.labflow.user.exception.UserNotFoundException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@Transactional(readOnly = true)
public class TokenRefreshService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenHasher refreshTokenHasher;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final AccessTokenProvider accessTokenProvider;
    private final UserRepository userRepository;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public TokenRefreshService(
            RefreshTokenRepository refreshTokenRepository,
            RefreshTokenHasher refreshTokenHasher,
            RefreshTokenGenerator refreshTokenGenerator,
            AccessTokenProvider accessTokenProvider,
            UserRepository userRepository,
            IdGenerator idGenerator,
            Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenHasher = refreshTokenHasher;
        this.refreshTokenGenerator = refreshTokenGenerator;
        this.accessTokenProvider = accessTokenProvider;
        this.userRepository = userRepository;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Transactional
    public TokenRefreshResult refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new IllegalArgumentException("Refresh token must not be null or blank");
        }

        String tokenHash = refreshTokenHasher.hash(rawRefreshToken);

        RefreshToken currentToken =
                refreshTokenRepository
                        .findByTokenHashForUpdate(tokenHash)
                        .orElseThrow(InvalidRefreshTokenException::new);

        Instant now = clock.instant();

        currentToken.validateUsable(now);

        User user =
                userRepository
                        .findById(currentToken.getUserId())
                        .orElseThrow(UserNotFoundException::new);

        currentToken.revoke(now);

        refreshTokenRepository.revoke(currentToken.getId(), currentToken.getRevokedAt());

        String newAccessToken = accessTokenProvider.createAccessToken(user.getId(), user.getRole());

        IssuedRefreshToken issuedRefreshToken = refreshTokenGenerator.generate();

        RefreshToken newRefreshToken =
                RefreshToken.create(
                        RefreshTokenId.of(idGenerator.nextId()),
                        user.getId(),
                        refreshTokenHasher.hash(issuedRefreshToken.rawToken()),
                        issuedRefreshToken.expiresAt(),
                        now);
        refreshTokenRepository.save(newRefreshToken);

        return new TokenRefreshResult(
                newAccessToken, issuedRefreshToken.rawToken(), issuedRefreshToken.expiresAt());
    }
}
