package com.dju.labflow.sdk.presentation;

import com.dju.labflow.run.run.application.RunFailCommand;
import jakarta.validation.constraints.*;

/** 실행 실패 사유. 같은 사유의 재전송은 기존 종료 결과를 유지한다. */
public record RunFailRequest(@NotBlank @Size(max = 4000) String error) {
    public RunFailCommand toCommand() {
        return new RunFailCommand(error);
    }
}
