package com.dju.labflow.project.key.application;

import com.dju.labflow.project.key.domain.ProjectApiKeyPrincipal;
import com.dju.labflow.project.key.exception.InvalidProjectApiKeyException;
import com.dju.labflow.project.key.exception.ProjectApiKeyAuthenticationUnavailableException;
import com.dju.labflow.project.key.util.ProjectApiKeyHasher;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

/** 일시적 DB 충돌만 제한적으로 재시도한다. 매 시도는 별도 Bean의 새 트랜잭션이다. */
@Service
public class ProjectApiKeyAuthenticationService {

    private static final int MAX_ATTEMPTS = 3;
    private final ProjectApiKeyAuthenticationAttempt attempt;
    private final ProjectApiKeyHasher projectApiKeyHasher;

    public ProjectApiKeyAuthenticationService(
            ProjectApiKeyAuthenticationAttempt attempt, ProjectApiKeyHasher projectApiKeyHasher) {
        this.attempt = attempt;
        this.projectApiKeyHasher = projectApiKeyHasher;
    }

    public ProjectApiKeyPrincipal authenticate(String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            throw new InvalidProjectApiKeyException("Invalid API key");
        }
        String keyHash = projectApiKeyHasher.hash(rawApiKey);
        for (int number = 1; ; number++) {
            try {
                // The proxy commits (or rolls back) before this call returns or throws.
                return attempt.authenticate(keyHash);
            } catch (RuntimeException failure) {
                if (failure instanceof AuthenticationException || !isRetryableConflict(failure))
                    throw failure;
                if (number == MAX_ATTEMPTS)
                    throw new ProjectApiKeyAuthenticationUnavailableException(failure);
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextLong(10, 51));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ProjectApiKeyAuthenticationUnavailableException(interrupted);
                }
            }
        }
    }

    private boolean isRetryableConflict(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure;
                cause != null && visited.add(cause);
                cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                Set<SQLException> chained = Collections.newSetFromMap(new IdentityHashMap<>());
                for (SQLException detail = sql;
                        detail != null && chained.add(detail);
                        detail = detail.getNextException()) {
                    if (detail.getErrorCode() == 1020
                            || detail.getErrorCode() == 1213
                            || detail.getErrorCode() == 1205
                            || "40001".equals(detail.getSQLState())) return true;
                }
            }
        }
        return false;
    }
}
