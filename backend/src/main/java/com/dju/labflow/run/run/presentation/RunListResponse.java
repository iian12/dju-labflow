package com.dju.labflow.run.run.presentation;

import com.dju.labflow.run.run.application.RunHeartbeatPolicy;
import com.dju.labflow.run.run.domain.RunPage;
import java.util.List;
import java.util.Map;

/** 페이지와 프로젝트 전체 상태 집계를 함께 반환한다. */
public record RunListResponse(
        List<RunQueryResponse> items,
        int page,
        int size,
        long totalElements,
        long totalPages,
        Map<String, Long> counts,
        long projectTotal,
        List<String> availableMetrics) {
    public static RunListResponse from(RunPage page, RunHeartbeatPolicy heartbeats) {
        return new RunListResponse(
                page.items().stream()
                        .map(run -> RunQueryResponse.from(run, heartbeats.status(run)))
                        .toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages(),
                Map.of(
                        "RUNNING",
                        page.running(),
                        "COMPLETED",
                        page.completed(),
                        "FAILED",
                        page.failed()),
                page.projectTotal(),
                page.availableMetrics());
    }
}
