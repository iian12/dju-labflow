package com.dju.labflow.run.systemmetric.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;

/** 시스템 지표 스냅샷 식별자. */
@Embeddable
public record RunSystemMetricId(Long value) implements Serializable {
    public RunSystemMetricId {
        if (value == null) throw new IllegalArgumentException("System metric ID must not be null");
    }

    public static RunSystemMetricId of(Long value) {
        return new RunSystemMetricId(value);
    }
}
