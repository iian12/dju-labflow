package com.dju.labflow.auth.domain;

import jakarta.persistence.Embeddable;

import java.io.Serializable;

@Embeddable
public record RefreshTokenId(Long value) implements Serializable {

    public RefreshTokenId {
        if (value == null) {
            throw new IllegalArgumentException("Refresh token ID must not be null");
        }
    }

    public static RefreshTokenId of(Long value) {
        return new RefreshTokenId(value);
    }
}
