package com.dju.labflow.project.key.domain;

import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.user.domain.UserId;

public record ProjectApiKeyPrincipal(
        ProjectApiKeyId apiKeyId,
        ProjectId projectId,
        UserId createdBy
) {
}