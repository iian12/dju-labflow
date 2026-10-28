package com.dju.labflow.project.key.presentation;

import com.dju.labflow.project.key.application.IssueProjectApiKeyResult;
import io.swagger.v3.oas.annotations.media.Schema;

/** 발급 직후 한 번만 노출하는 API 키 원문 응답. */
public record IssueProjectApiKeyResponse(
        @Schema(
                        description = "발급 시 한 번만 반환되는 API 키 원문. 서버에는 해시만 저장합니다.",
                        example = "lfp_prefix_secret")
                String rawApiKey) {
    public static IssueProjectApiKeyResponse from(IssueProjectApiKeyResult result) {
        return new IssueProjectApiKeyResponse(result.rawApiKey());
    }
}
