package com.dju.labflow.sdk.presentation;

import com.dju.labflow.run.run.domain.Run;
import com.dju.labflow.run.run.domain.RunStatus;
import java.time.Instant;
import java.util.Map;

/** SDK 실행 조회 응답. 영속성 버전이나 인증 정보는 노출하지 않는다. */
public record RunResponse(
        Long runId,
        Long projectId,
        String name,
        Map<String, String> metadata,
        RunStatus status,
        Instant startedAt,
        Instant endedAt,
        String error) {
    public static RunResponse from(Run run) {
        return new RunResponse(
                run.getId().value(),
                run.getProjectId().value(),
                run.getName(),
                run.getMetadata(),
                run.getStatus(),
                run.getStartedAt(),
                run.getEndedAt(),
                run.getError());
    }
}
