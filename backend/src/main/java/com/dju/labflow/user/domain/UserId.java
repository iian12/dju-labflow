package com.dju.labflow.user.domain;

import jakarta.persistence.Embeddable;

import java.io.Serializable;

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
