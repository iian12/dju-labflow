package com.dju.labflow.auth.presentation;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 단일 인스턴스의 IP별 시간당 제한. 프록시 헤더를 직접 신뢰하지 않는다. */
@Component
public class VerificationRequestLimiter {
    private final Cache<String, AtomicInteger> requests =
            Caffeine.newBuilder()
                    .maximumSize(100_000)
                    .expireAfterWrite(Duration.ofHours(1))
                    .build();

    public void check(String ip, boolean verification) {
        String key = (verification ? "verify:" : "send:") + ip;
        if (requests.get(key, ignored -> new AtomicInteger()).incrementAndGet()
                > (verification ? 100 : 10)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests");
        }
    }
}
