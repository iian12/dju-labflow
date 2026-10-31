package com.dju.labflow.run.domain;

import jakarta.persistence.Embeddable;

import java.io.Serializable;

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
