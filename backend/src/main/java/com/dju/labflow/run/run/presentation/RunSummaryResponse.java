package com.dju.labflow.run.run.presentation;

import com.dju.labflow.run.run.domain.RunSummary;
import java.util.List;

/** 목록 화면에 필요한 실행별 최신 지표 응답. */
public record RunSummaryResponse(
        Long runId, Long lastStep, List<RunRecordsResponse.Metric> metrics) {
    public static RunSummaryResponse from(RunSummary summary) {
        return new RunSummaryResponse(
                summary.runId(),
                summary.lastStep(),
                summary.metrics().stream()
                        .map(
                                m ->
                                        new RunRecordsResponse.Metric(
                                                m.getId().value(),
                                                m.getKey(),
                                                m.getValue(),
                                                m.getEpoch(),
                                                m.getStep(),
                                                m.getElapsedTime(),
                                                m.getRecordedAt()))
                        .toList());
    }
}
