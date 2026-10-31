package com.dju.labflow.run.metric.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;

/** 한 실행의 숫자 지표 한 점. 문자열과 비유한 수는 허용하지 않는다. 식별자. */
@Embeddable
public record RunMetricId(Long value) implements Serializable {
    public RunMetricId {
        if (value == null) throw new IllegalArgumentException("RunMetric ID must not be null");
    }

    public static RunMetricId of(Long value) {
        return new RunMetricId(value);
    }
}
