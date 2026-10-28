package com.dju.labflow.project.key.application;

import com.dju.labflow.auth.application.AccountAccessPolicy;
import com.dju.labflow.project.key.domain.ProjectApiKey;
import com.dju.labflow.project.key.domain.ProjectApiKeyPrincipal;
import com.dju.labflow.project.key.domain.repository.ProjectApiKeyRepository;
import com.dju.labflow.project.key.exception.InvalidProjectApiKeyException;
import com.dju.labflow.project.key.util.ProjectApiKeyHasher;
import com.dju.labflow.user.domain.repository.UserRepository;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

/** SDK 키 해시와 폐기 상태, 소유자의 현재 계정 상태를 확인해 인증 주체를 만든다. */
@Service
public class ProjectApiKeyAuthenticationService {

    private final ProjectApiKeyRepository projectApiKeyRepository;
    private final ProjectApiKeyHasher projectApiKeyHasher;
    private final UserRepository userRepository;

    public ProjectApiKeyAuthenticationService(
            ProjectApiKeyRepository projectApiKeyRepository,
            ProjectApiKeyHasher projectApiKeyHasher,
            UserRepository userRepository) {
        this.projectApiKeyRepository = projectApiKeyRepository;
        this.projectApiKeyHasher = projectApiKeyHasher;
        this.userRepository = userRepository;
    }

    public ProjectApiKeyPrincipal authenticate(String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            throw new InvalidProjectApiKeyException("Invalid API key");
        }
        String keyHash = projectApiKeyHasher.hash(rawApiKey);
        ProjectApiKey apiKey =
                projectApiKeyRepository
                        .findByKeyHash(keyHash)
                        .orElseThrow(() -> new InvalidProjectApiKeyException("Invalid API key"));
        try {
            apiKey.validateUsable();
            // JWT뿐 아니라 기존 SDK 키도 소유자가 차단되면 사용할 수 없다.
            var owner =
                    userRepository
                            .findById(apiKey.getCreatedBy())
                            .orElseThrow(
                                    () -> new InvalidProjectApiKeyException("Invalid account"));
            AccountAccessPolicy.requireActive(owner);
        } catch (IllegalStateException | AuthenticationException e) {
            throw new InvalidProjectApiKeyException("Invalid API key");
        }

        return new ProjectApiKeyPrincipal(
                apiKey.getId(), apiKey.getProjectId(), apiKey.getCreatedBy());
    }
}
