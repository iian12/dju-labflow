package com.dju.labflow.sdk.request.domain;

import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.run.run.domain.RunId;
import java.time.Instant;

/** 성공한 SDK 쓰기의 식별자와 본문 해시. 원문 로그나 인증 키는 저장하지 않는다. */
public record SdkRequest(
        Long id,
        ProjectId projectId,
        String requestKey,
        String fingerprint,
        RunId resultRunId,
        Instant createdAt) {}
