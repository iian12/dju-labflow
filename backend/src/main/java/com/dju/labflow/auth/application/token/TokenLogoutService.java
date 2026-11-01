package com.dju.labflow.auth.application.token;

import com.dju.labflow.auth.domain.RefreshTokenHasher;
import com.dju.labflow.auth.domain.RefreshTokenRepository;
import java.time.Clock;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 현재 refresh 토큰을 폐기한다. 계정/토큰 상태에 관계없이 브라우저 쿠키를 삭제할 수 있다. */
@Service
public class TokenLogoutService {
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenHasher refreshTokenHasher;
    private final Clock clock;

    public TokenLogoutService(
            RefreshTokenRepository refreshTokenRepository,
            RefreshTokenHasher refreshTokenHasher,
            Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenHasher = refreshTokenHasher;
        this.clock = clock;
    }

    /** 만료/폐기/누락 토큰에도 멱등적으로 동작한다. 다른 기기의 세션은 변경하지 않는다. */
    @Transactional
    public void logout(@Nullable String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) return;
        var token =
                refreshTokenRepository.findByTokenHashForUpdate(
                        refreshTokenHasher.hash(rawRefreshToken));
        if (token.isPresent() && !token.get().isRevoked())
            refreshTokenRepository.revoke(token.get().getId(), clock.instant());
    }
}
