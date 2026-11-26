package com.dju.labflow.sdk.idempotency;

import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 유효기간은 요청 시 판정하고, 만료 기록은 작은 배치로 삭제한다. */
@Configuration
@EnableScheduling
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "labflow.idempotency.cleanup-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class IdempotencyCleanup {
    private final IdempotencyStore store;

    public IdempotencyCleanup(IdempotencyStore store) {
        this.store = store;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void cleanup() {
        try {
            store.deleteExpired();
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(getClass())
                    .warn("Idempotency cleanup deferred ({})", failure.getClass().getSimpleName());
        }
    }
}
