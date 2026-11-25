package com.dju.labflow.project.key.application;

import com.dju.labflow.auth.application.AccountAccessPolicy;
import com.dju.labflow.project.key.domain.ProjectApiKey;
import com.dju.labflow.project.key.domain.ProjectApiKeyPrincipal;
import com.dju.labflow.project.key.domain.repository.ProjectApiKeyRepository;
import com.dju.labflow.project.key.exception.InvalidProjectApiKeyException;
import com.dju.labflow.user.domain.User;
import com.dju.labflow.user.domain.repository.UserRepository;
import java.time.Clock;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 최신 키·소유자 상태를 확인하고 사용 시각을 기록하는 단일 인증 트랜잭션. */
@Service
public class ProjectApiKeyAuthenticationAttempt {
    private final ProjectApiKeyRepository projectApiKeyRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public ProjectApiKeyAuthenticationAttempt(
            ProjectApiKeyRepository projectApiKeyRepository,
            UserRepository userRepository,
            Clock clock) {
        this.projectApiKeyRepository = projectApiKeyRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 5)
    public ProjectApiKeyPrincipal authenticate(String keyHash) {
        ProjectApiKey apiKey =
                projectApiKeyRepository
                        .findByKeyHash(keyHash)
                        .orElseThrow(() -> new InvalidProjectApiKeyException("Invalid API key"));
        try {
            apiKey.validateUsable();
            User owner =
                    userRepository
                            .findById(apiKey.getCreatedBy())
                            .orElseThrow(
                                    () -> new InvalidProjectApiKeyException("Invalid account"));
            AccountAccessPolicy.requireActive(owner);
        } catch (IllegalStateException | AuthenticationException failure) {
            throw new InvalidProjectApiKeyException("Invalid API key");
        }
        // Keep the active/revoked predicate and monotonic timestamp update in the same transaction.
        if (!projectApiKeyRepository.recordUsage(apiKey.getId(), clock.instant()))
            throw new InvalidProjectApiKeyException("Invalid API key");
        return new ProjectApiKeyPrincipal(
                apiKey.getId(), apiKey.getProjectId(), apiKey.getCreatedBy());
    }
}
