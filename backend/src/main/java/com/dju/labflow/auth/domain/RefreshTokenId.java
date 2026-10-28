package com.dju.labflow.auth.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;

/** 리프레시 토큰 ID를 다른 종류의 ID와 구분하는 값 객체. */
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
