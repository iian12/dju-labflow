package com.dju.labflow.project.key.domain;

import jakarta.persistence.Embeddable;

import java.io.Serializable;

/** 프로젝트 API 키 ID를 다른 도메인의 ID와 구분하는 값 객체. */
@Embeddable
public record ProjectApiKeyId(Long value) implements Serializable {

    public ProjectApiKeyId {
        if (value == null) throw new IllegalStateException("Value is null");
    }

    public static ProjectApiKeyId of(Long value) {
        return new ProjectApiKeyId(value);
    }
}
