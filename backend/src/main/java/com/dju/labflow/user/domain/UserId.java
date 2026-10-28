package com.dju.labflow.user.domain;

import jakarta.persistence.Embeddable;

import java.io.Serializable;

/** 사용자 ID를 다른 종류의 ID와 혼용하지 않도록 하는 값 객체. */
@Embeddable
public record UserId(Long value) implements Serializable {

    public UserId {
        if (value == null) {
            throw new IllegalArgumentException("User ID must not be null");
        }
    }

    public static UserId of(Long value) {
        return new UserId(value);
    }
}
