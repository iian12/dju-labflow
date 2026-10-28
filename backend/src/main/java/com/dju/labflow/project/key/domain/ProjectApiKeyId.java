package com.dju.labflow.project.key.domain;

import jakarta.persistence.Embeddable;

import java.io.Serializable;

@Embeddable
public record ProjectApiKeyId(Long value) implements Serializable {

    public ProjectApiKeyId {
        if (value == null) throw new IllegalStateException("Value is null");
    }

    public static ProjectApiKeyId of(Long value) {
        return new ProjectApiKeyId(value);
    }
}
