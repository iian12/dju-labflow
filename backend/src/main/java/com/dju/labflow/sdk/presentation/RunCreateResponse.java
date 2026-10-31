package com.dju.labflow.sdk.presentation;

import com.dju.labflow.run.run.domain.RunId;

/** 생성된 실행의 식별자 응답. */
public record RunCreateResponse(Long runId) {
    public static RunCreateResponse from(RunId runId) {
        return new RunCreateResponse(runId.value());
    }
}
