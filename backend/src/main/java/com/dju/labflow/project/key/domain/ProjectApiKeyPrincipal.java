package com.dju.labflow.project.key.domain;

import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.user.domain.UserId;

/** SDK 인증 주체. 사용자 JWT와 달리 키 ID와 접근 대상 프로젝트 ID를 포함한다. */
public record ProjectApiKeyPrincipal(
        ProjectApiKeyId apiKeyId, ProjectId projectId, UserId createdBy) {}
