package com.dju.labflow.project.project.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;

/** 프로젝트 식별자를 다른 도메인 ID와 구분하는 값 객체. */
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
