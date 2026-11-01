package com.dju.labflow.run.run.presentation;

import com.dju.labflow.run.log.domain.*;
import com.dju.labflow.run.run.application.RunRecords;
import com.dju.labflow.run.systemmetric.domain.GpuMetric;
import java.time.Instant;
import java.util.List;

/** 기록별 식별자와 원문/수치를 보존하는 웹 조회 응답. */
public record RunRecordsResponse(
        List<Parameter> parameters,
        List<Metric> metrics,
        List<Log> logs,
        List<Text> texts,
        List<SystemMetric> systemMetrics,
        int limit) {
    public record Parameter(String key, String value) {}

    public record Metric(
            Long id,
            String key,
            Double value,
            Integer epoch,
            Long step,
            Double elapsedTime,
            Instant recordedAt) {}

    public record Log(
            Long id, String message, RunLogLevel level, RunLogSource source, Instant recordedAt) {}

    public record Text(Long id, String key, String text, Long step, Instant recordedAt) {}

    public record SystemMetric(
            Long id,
            Double cpuUtilization,
            Long ramUsed,
            Long ramTotal,
            List<GpuMetric> gpus,
            Double elapsedTime,
            Instant recordedAt) {}

    public static RunRecordsResponse from(RunRecords records) {
        return new RunRecordsResponse(
                records.parameters().stream()
                        .map(p -> new Parameter(p.getKey(), p.getValue()))
                        .toList(),
                records.metrics().stream()
                        .map(
                                m ->
                                        new Metric(
                                                m.getId().value(),
                                                m.getKey(),
                                                m.getValue(),
                                                m.getEpoch(),
                                                m.getStep(),
                                                m.getElapsedTime(),
                                                m.getRecordedAt()))
                        .toList(),
                records.logs().stream()
                        .map(
                                l ->
                                        new Log(
                                                l.getId().value(),
                                                l.getMessage(),
                                                l.getLevel(),
                                                l.getSource(),
                                                l.getRecordedAt()))
                        .toList(),
                records.texts().stream()
                        .map(
                                t ->
                                        new Text(
                                                t.getId().value(),
                                                t.getKey(),
                                                t.getText(),
                                                t.getStep(),
                                                t.getRecordedAt()))
                        .toList(),
                records.systemMetrics().stream()
                        .map(
                                s ->
                                        new SystemMetric(
                                                s.getId().value(),
                                                s.getCpuUtilization(),
                                                s.getRamUsed(),
                                                s.getRamTotal(),
                                                s.getGpus(),
                                                s.getElapsedTime(),
                                                s.getRecordedAt()))
                        .toList(),
                records.limit());
    }
}
