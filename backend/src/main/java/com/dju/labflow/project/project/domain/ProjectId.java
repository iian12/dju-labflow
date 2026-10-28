package com.dju.labflow.project.project.domain;

import jakarta.persistence.Embeddable;

import java.io.Serializable;

@Embeddable
public record ProjectId(Long value) implements Serializable {
    public ProjectId {
        if (value == null) {
            throw new IllegalStateException("Value is null");
        }
    }

    public static ProjectId of(Long value) {
        return new ProjectId(value);
    }
}
