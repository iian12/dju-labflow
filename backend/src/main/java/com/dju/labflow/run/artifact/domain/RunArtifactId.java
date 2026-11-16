package com.dju.labflow.run.artifact.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;

/** 실행 결과 파일의 식별자. */
@Embeddable
public record RunArtifactId(Long value) implements Serializable {
    public RunArtifactId {
        if (value == null) throw new IllegalArgumentException("RunArtifact ID must not be null");
    }

    public static RunArtifactId of(Long value) {
        return new RunArtifactId(value);
    }
}
