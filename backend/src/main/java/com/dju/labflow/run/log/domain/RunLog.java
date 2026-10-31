package com.dju.labflow.run.log.domain;

import com.dju.labflow.global.validation.InputValidation;
import com.dju.labflow.run.run.domain.RunId;
import java.time.Instant;
import lombok.Getter;

/** 실행 중 발생한 텍스트 메시지. 원문과 수집 출처를 보존한다. */
@Getter
public class RunLog {
    private final RunLogId id;
    private final RunId runId;
    private final String message;
    private final RunLogLevel level;
    private final RunLogSource source;
    private final Instant recordedAt;

    private RunLog(
            RunLogId id,
            RunId runId,
            String message,
            RunLogLevel level,
            RunLogSource source,
            Instant recordedAt) {
        this.id = InputValidation.required(id, "RunLog ID");
        this.runId = InputValidation.required(runId, "Run ID");
        this.message =
                InputValidation.optionalText(
                        InputValidation.required(message, "Log message"), "Log message", 16000);
        this.level = InputValidation.required(level, "Log level");
        this.source = InputValidation.required(source, "Log source");
        this.recordedAt = InputValidation.required(recordedAt, "Recording time");
    }

    public static RunLog create(
            RunLogId id,
            RunId runId,
            String message,
            RunLogLevel level,
            RunLogSource source,
            Instant recordedAt) {
        return new RunLog(id, runId, message, level, source, recordedAt);
    }

    public static RunLog restore(
            RunLogId id,
            RunId runId,
            String message,
            RunLogLevel level,
            RunLogSource source,
            Instant recordedAt) {
        return new RunLog(id, runId, message, level, source, recordedAt);
    }
}
