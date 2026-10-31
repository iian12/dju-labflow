package com.dju.labflow.run.log.application;

import com.dju.labflow.run.log.domain.RunLogLevel;
import com.dju.labflow.run.log.domain.RunLogSource;
import jakarta.validation.constraints.*;
import java.time.Instant;

/** HTTP DTO와 분리한 실행 서비스 입력. 도메인에서도 필수 불변식을 검사한다. */
public record RunLogCommand(
        @NotNull @Size(max = 16000) String message,
        @NotNull RunLogLevel level,
        @NotNull RunLogSource source,
        Instant recordedAt) {}
