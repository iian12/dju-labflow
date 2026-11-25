package com.dju.labflow.auth.presentation;

import com.dju.labflow.auth.application.PersistentRequestLimiter;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LoginRequestLimiter {
    private final PersistentRequestLimiter requests;
    private final int ipLimit;
    private final int accountIpLimit;

    public LoginRequestLimiter(
            PersistentRequestLimiter requests,
            @Value("${labflow.auth.login.ip-limit:60}") int ipLimit,
            @Value("${labflow.auth.login.account-ip-limit:10}") int accountIpLimit) {
        if (ipLimit < 1 || accountIpLimit < 1) {
            throw new IllegalArgumentException("Login request limits must be positive");
        }
        this.requests = requests;
        this.ipLimit = ipLimit;
        this.accountIpLimit = accountIpLimit;
    }

    public void check(String ip, String email) {
        requests.check("login-ip", ip, ipLimit, Duration.ofMinutes(1));
        requests.check(
                "login-account-ip",
                ip + "\n" + email.trim().toLowerCase(Locale.ROOT),
                accountIpLimit,
                Duration.ofMinutes(15));
    }
}
