package com.dju.labflow.auth.infrastructure.cookie;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 인증/CSRF 쿠키가 공유하는 환경별 정책. 브라우저가 거부할 조합은 시작 시 검증한다. */
@ConfigurationProperties("jwt.cookie")
public record AuthCookieProperties(boolean secure, String sameSite) {
    public AuthCookieProperties {
        if (sameSite == null || !List.of("Strict", "Lax", "None").contains(sameSite)) {
            throw new IllegalArgumentException("Cookie SameSite must be Strict, Lax or None");
        }
        if ("None".equals(sameSite) && !secure) {
            throw new IllegalArgumentException("SameSite=None requires Secure cookies");
        }
    }
}
