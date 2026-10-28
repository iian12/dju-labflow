package com.dju.labflow.project.key.infrastructure.security;

import com.dju.labflow.project.key.domain.ProjectApiKeyPrincipal;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.List;

/** 검증을 통과한 프로젝트 API 키를 Spring Security 인증 객체로 표현한다. */
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
