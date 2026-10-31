package com.dju.labflow.sdk.presentation;

import com.dju.labflow.run.text.application.RunTextCommand;
import jakarta.validation.constraints.*;
import java.time.Instant;

/** 모델 입력·출력·정답 등의 텍스트 데이터. 빈 생성 결과와 원문 공백도 보존한다. */
public record TextCreateRequest(
        @NotBlank @Size(max = 100) String key,
        @NotNull @Size(max = 65536) String text,
        @PositiveOrZero Long step,
        Instant recordedAt) {
    public RunTextCommand toCommand() {
        return new RunTextCommand(key, text, step, recordedAt);
    }
}
