package com.dju.labflow.project.key.infrastructure.security;

import com.dju.labflow.project.key.domain.ProjectApiKeyPrincipal;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.List;

public class ProjectApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final ProjectApiKeyPrincipal principal;

    public ProjectApiKeyAuthenticationToken(ProjectApiKeyPrincipal principal) {
        super(List.of());
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public @Nullable Object getCredentials() {
        return null;
    }

    @Override
    public @Nullable Object getPrincipal() {
        return principal;
    }
}
