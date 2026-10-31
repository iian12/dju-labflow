package com.dju.labflow.sdk.presentation;

import com.dju.labflow.run.log.application.RunLogCommand;
import com.dju.labflow.run.log.domain.RunLogLevel;
import com.dju.labflow.run.log.domain.RunLogSource;
import jakarta.validation.constraints.*;
import java.time.Instant;

/** 실행 메시지와 중요도/출처를 전달한다. 숫자 메트릭과 실험 텍스트는 별도 요청이다. */
public record LogCreateRequest(
        @NotNull @Size(max = 16000) String message,
        @NotNull RunLogLevel level,
        @NotNull RunLogSource source,
        Instant recordedAt) {
    public RunLogCommand toCommand() {
        return new RunLogCommand(message, level, source, recordedAt);
    }
}
