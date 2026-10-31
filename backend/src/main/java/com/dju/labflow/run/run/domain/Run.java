package com.dju.labflow.run.run.domain;

import com.dju.labflow.global.validation.InputValidation;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.run.run.exception.RunStateConflictException;
import com.dju.labflow.user.domain.UserId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import lombok.Getter;

/** 개인 프로젝트의 실험 실행. 종료 상태와 소속은 유지하고 조회 버전을 보존한다. */
@Getter
public class Run {
    private final RunId id;
    private final ProjectId projectId;
    private final UserId createdBy;
    private final String name;
    private final Map<String, String> metadata;
    private RunStatus status;
    private final Instant startedAt;
    private Instant endedAt;
    private String error;
    private final Long version;

    private Run(
            RunId id,
            ProjectId projectId,
            UserId createdBy,
            String name,
            Map<String, String> metadata,
            RunStatus status,
            Instant startedAt,
            Instant endedAt,
            String error,
            Long version) {
        this.id = InputValidation.required(id, "Run ID");
        this.projectId = InputValidation.required(projectId, "Project ID");
        this.createdBy = InputValidation.required(createdBy, "Run owner");
        this.name = InputValidation.text(name, "Run name", 100);
        this.metadata = validateMetadata(metadata);
        this.status = InputValidation.required(status, "Run status");
        this.startedAt = InputValidation.required(startedAt, "Start time");
        this.endedAt = endedAt;
        this.error = error;
        this.version = version;
        if ((status == RunStatus.RUNNING) != (endedAt == null))
            throw new IllegalArgumentException("Terminal runs must have an end time");
        if (endedAt != null && endedAt.isBefore(startedAt))
            throw new IllegalArgumentException("End time must not precede start time");
        if (status == RunStatus.FAILED) InputValidation.text(error, "Failure reason", 4000);
        else if (error != null)
            throw new IllegalArgumentException("Only failed runs have a failure reason");
    }

    public static Run create(
            RunId id,
            ProjectId projectId,
            UserId createdBy,
            String name,
            Map<String, String> metadata,
            Instant startedAt) {
        return new Run(
                id,
                projectId,
                createdBy,
                name,
                metadata,
                RunStatus.RUNNING,
                startedAt,
                null,
                null,
                null);
    }

    public static Run restore(
            RunId id,
            ProjectId projectId,
            UserId createdBy,
            String name,
            Map<String, String> metadata,
            RunStatus status,
            Instant startedAt,
            Instant endedAt,
            String error,
            Long version) {
        return new Run(
                id,
                projectId,
                createdBy,
                name,
                metadata,
                status,
                startedAt,
                endedAt,
                error,
                InputValidation.required(version, "Run version"));
    }

    public void requireRunning() {
        if (status != RunStatus.RUNNING)
            throw new RunStateConflictException("Run has already ended");
    }

    /** 같은 종료 작업의 재전송은 저장한 결과를 유지한다. */
    public void complete(Instant now) {
        if (status == RunStatus.COMPLETED) return;
        requireRunning();
        validateEndTime(now);
        this.status = RunStatus.COMPLETED;
        this.endedAt = now;
    }

    /** 같은 실패 사유의 재전송은 멱등적으로 처리하며 종료 결과를 덮어쓰지 않는다. */
    public void fail(String error, Instant now) {
        InputValidation.text(error, "Failure reason", 4000);
        if (status == RunStatus.FAILED && Objects.equals(this.error, error)) return;
        requireRunning();
        validateEndTime(now);
        this.status = RunStatus.FAILED;
        this.endedAt = now;
        this.error = error;
    }

    private void validateEndTime(Instant now) {
        InputValidation.required(now, "End time");
        if (now.isBefore(startedAt))
            throw new IllegalArgumentException("End time must not precede start time");
    }

    private static Map<String, String> validateMetadata(Map<String, String> metadata) {
        if (metadata == null) return Map.of();
        if (metadata.size() > 100)
            throw new IllegalArgumentException("Metadata exceeds 100 entries");
        metadata.forEach(
                (key, value) -> {
                    InputValidation.text(key, "Metadata key", 100);
                    InputValidation.optionalText(
                            InputValidation.required(value, "Metadata value"),
                            "Metadata value",
                            2000);
                });
        return Map.copyOf(metadata);
    }
}
