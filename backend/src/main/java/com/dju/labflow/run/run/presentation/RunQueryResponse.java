package com.dju.labflow.run.run.presentation;

import com.dju.labflow.run.run.domain.Run;
import com.dju.labflow.run.run.domain.RunStatus;
import java.time.Instant;
import java.util.Map;

/** 웹 실행 상세. 내부 버전이나 소유자 정보는 노출하지 않는다. */
public record RunQueryResponse(
        Long runId,
        Long projectId,
        String name,
        Map<String, String> metadata,
        RunStatus status,
        Instant startedAt,
        Instant endedAt,
        String error) {
    public static RunQueryResponse from(Run run) {
        return new RunQueryResponse(
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
