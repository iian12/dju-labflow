package com.dju.labflow.global.config;

import com.dju.labflow.auth.infrastructure.cookie.AuthCookieProperties;
import com.dju.labflow.auth.infrastructure.jwt.JwtProperties;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 토큰 생성과 시간 의존 로직에 필요한 JWT 설정, UTC 시계, 난수 생성기를 등록한다. */
@Configuration
@EnableConfigurationProperties({JwtProperties.class, AuthCookieProperties.class})
public class AuthConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public SecureRandom secureRandom() {
        return new SecureRandom();
    }
}
