package com.dju.labflow.run.run.presentation;

import com.dju.labflow.run.log.domain.RunLog;
import com.dju.labflow.run.metric.domain.RunMetric;
import com.dju.labflow.run.run.application.RunRecordPage;
import com.dju.labflow.run.systemmetric.domain.RunSystemMetric;
import com.dju.labflow.run.text.domain.RunText;
import java.util.List;

/** 기존 묶음 조회와 같은 항목 DTO를 사용하는 커서 페이지 응답. */
public record RunRecordPageResponse(
        List<?> items,
        int limit,
        boolean hasMore,
        String beforeCursor,
        String afterCursor,
        String nextCursor) {
    public static RunRecordPageResponse from(RunRecordPage<?> page) {
        return new RunRecordPageResponse(
                page.items().stream().map(RunRecordPageResponse::record).toList(),
                page.limit(),
                page.hasMore(),
                page.beforeCursor(),
                page.afterCursor(),
                page.nextCursor());
    }

    private static Object record(Object value) {
        return switch (value) {
            case RunMetric m ->
                    new RunRecordsResponse.Metric(
                            m.getId().value(),
                            m.getKey(),
                            m.getValue(),
                            m.getEpoch(),
                            m.getStep(),
                            m.getElapsedTime(),
                            m.getRecordedAt());
            case RunLog l ->
                    new RunRecordsResponse.Log(
                            l.getId().value(),
                            l.getMessage(),
                            l.getLevel(),
                            l.getSource(),
                            l.getRecordedAt());
            case RunText t ->
                    new RunRecordsResponse.Text(
                            t.getId().value(),
                            t.getKey(),
                            t.getText(),
                            t.getStep(),
                            t.getRecordedAt());
            case RunSystemMetric s ->
                    new RunRecordsResponse.SystemMetric(
                            s.getId().value(),
                            s.getCpuUtilization(),
                            s.getRamUsed(),
                            s.getRamTotal(),
                            s.getGpus(),
                            s.getElapsedTime(),
                            s.getRecordedAt());
            default -> throw new IllegalArgumentException("Unknown record type");
        };
    }
}
