package com.dju.labflow.run.run.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;

/** 실험 실행 식별자. 다른 도메인의 식별자와 구분한다. */
@Embeddable
public record RunId(Long value) implements Serializable {

    public RunId {
        if (value == null) {
            throw new IllegalArgumentException("Run ID must not be null");
        }
    }

    public static RunId of(Long value) {
        return new RunId(value);
    }
}
