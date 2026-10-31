package com.dju.labflow.run.text.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;

/** 모델 입력·출력·정답 등의 텍스트 샘플. 실행 메시지와 분리한다. 식별자. */
@Embeddable
public record RunTextId(Long value) implements Serializable {
    public RunTextId {
        if (value == null) throw new IllegalArgumentException("RunText ID must not be null");
    }

    public static RunTextId of(Long value) {
        return new RunTextId(value);
    }
}
