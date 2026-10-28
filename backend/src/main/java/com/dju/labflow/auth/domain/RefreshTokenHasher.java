package com.dju.labflow.auth.domain;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;

@Component
public class RefreshTokenHasher {

    public String hash(String rawToken) {
        Objects.requireNonNull(rawToken, "Refresh token must not be null");

        if (rawToken.isBlank()) {
            throw new IllegalArgumentException("Refresh token must not be blank");
        }

        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hashedBytes = digest.digest(
                    rawToken.getBytes(StandardCharsets.UTF_8)
            );

            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(hashedBytes);

        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 표준 알고리즘이므로 일반적인 JVM에서는 발생하지 않음.
            throw new IllegalStateException(
                    "SHA-256 algorithm is not available.",
                    e
            );
        }
    }
}
