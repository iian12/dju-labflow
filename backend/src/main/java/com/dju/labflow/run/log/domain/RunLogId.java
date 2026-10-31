package com.dju.labflow.run.log.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;

/** 실행 중 발생한 텍스트 메시지. 원문과 수집 출처를 보존한다. 식별자. */
@Embeddable
public record RunLogId(Long value) implements Serializable {
    public RunLogId {
        if (value == null) throw new IllegalArgumentException("RunLog ID must not be null");
    }

    public static RunLogId of(Long value) {
        return new RunLogId(value);
    }
}
