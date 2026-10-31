package com.dju.labflow.run.parameter.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;

/** 실행에 한 번 정한 설정 값. 같은 키의 값은 변경하지 않는다. 식별자. */
@Embeddable
public record RunParameterId(Long value) implements Serializable {
    public RunParameterId {
        if (value == null) throw new IllegalArgumentException("RunParameter ID must not be null");
    }

    public static RunParameterId of(Long value) {
        return new RunParameterId(value);
    }
}
