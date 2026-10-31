package com.dju.labflow.run.text.domain;

import com.dju.labflow.global.validation.InputValidation;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.run.run.domain.RunValidation;
import java.time.Instant;
import lombok.Getter;

/** 모델 입력·출력·정답 등의 텍스트 샘플. 실행 메시지와 분리한다. */
@Getter
public class RunText {
    private final RunTextId id;
    private final RunId runId;
    private final String key;
    private final String text;
    private final Long step;
    private final Instant recordedAt;

    private RunText(
            RunTextId id, RunId runId, String key, String text, Long step, Instant recordedAt) {
        this.id = InputValidation.required(id, "RunText ID");
        this.runId = InputValidation.required(runId, "Run ID");
        this.key = InputValidation.text(key, "Text key", 100);
        this.text =
                InputValidation.optionalText(
                        InputValidation.required(text, "Text content"), "Text content", 65536);
        this.step = RunValidation.nonNegative(step, "Step");
        this.recordedAt = InputValidation.required(recordedAt, "Recording time");
    }

    public static RunText create(
            RunTextId id, RunId runId, String key, String text, Long step, Instant recordedAt) {
        return new RunText(id, runId, key, text, step, recordedAt);
    }

    public static RunText restore(
            RunTextId id, RunId runId, String key, String text, Long step, Instant recordedAt) {
        return new RunText(id, runId, key, text, step, recordedAt);
    }
}
