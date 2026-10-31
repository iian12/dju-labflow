package com.dju.labflow.run.parameter.domain;

import com.dju.labflow.global.validation.InputValidation;
import com.dju.labflow.run.run.domain.RunId;
import java.time.Instant;
import lombok.Getter;

/** 실행에 한 번 정한 설정 값. 같은 키의 값은 변경하지 않는다. */
@Getter
public class RunParameter {
    private final RunParameterId id;
    private final RunId runId;
    private final String key;
    private final String value;
    private final Instant createdAt;

    private RunParameter(
            RunParameterId id, RunId runId, String key, String value, Instant createdAt) {
        this.id = InputValidation.required(id, "RunParameter ID");
        this.runId = InputValidation.required(runId, "Run ID");
        this.key = InputValidation.text(key, "Parameter key", 100);
        this.value =
                InputValidation.optionalText(
                        InputValidation.required(value, "Parameter value"),
                        "Parameter value",
                        2000);
        this.createdAt = InputValidation.required(createdAt, "Creation time");
    }

    public static RunParameter create(
            RunParameterId id, RunId runId, String key, String value, Instant createdAt) {
        return new RunParameter(id, runId, key, value, createdAt);
    }

    public static RunParameter restore(
            RunParameterId id, RunId runId, String key, String value, Instant createdAt) {
        return new RunParameter(id, runId, key, value, createdAt);
    }
}
