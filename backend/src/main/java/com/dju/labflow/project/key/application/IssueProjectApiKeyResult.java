package com.dju.labflow.project.key.application;

import com.dju.labflow.project.key.domain.ProjectApiKeyId;

/** 발급한 키의 식별자와 한 번만 반환할 원문을 담는 서비스 결과. */
public record IssueProjectApiKeyResult(ProjectApiKeyId apiKeyId, String rawApiKey) {}
