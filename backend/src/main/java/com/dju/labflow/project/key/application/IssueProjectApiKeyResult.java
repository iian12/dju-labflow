package com.dju.labflow.project.key.application;

import com.dju.labflow.project.key.domain.ProjectApiKeyId;

public record IssueProjectApiKeyResult(ProjectApiKeyId apiKeyId, String rawApiKey) {}
