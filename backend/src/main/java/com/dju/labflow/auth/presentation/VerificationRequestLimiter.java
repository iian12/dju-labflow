package com.dju.labflow.auth.presentation;

import com.dju.labflow.auth.application.PersistentRequestLimiter;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Shared IP budgets for verification and mail requests. */
@Component
public class VerificationRequestLimiter {
    private final PersistentRequestLimiter requests;

    public VerificationRequestLimiter(PersistentRequestLimiter requests) {
        this.requests = requests;
    }

    public void check(String ip, boolean verification) {
        requests.check(
                verification ? "verify" : "send", ip, verification ? 100 : 10, Duration.ofHours(1));
    }
}
