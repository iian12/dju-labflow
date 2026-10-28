package com.dju.labflow.project.key.presentation;

import com.dju.labflow.project.key.application.IssueProjectApiKeyResult;

public record IssueProjectApiKeyResponse(String rawApiKey) {
    public static IssueProjectApiKeyResponse from(IssueProjectApiKeyResult result) {
        return new IssueProjectApiKeyResponse(result.rawApiKey());
    }
}
