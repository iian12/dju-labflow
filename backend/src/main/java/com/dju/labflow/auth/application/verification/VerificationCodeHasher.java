package com.dju.labflow.auth.application.verification;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 짧은 코드의 오프라인 대입을 막고 사용자별로 해시를 분리한다. */
@Component
public class VerificationCodeHasher {
    private final byte[] secret;

    public VerificationCodeHasher(@Value("${auth.email-verification.secret:}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String hash(Long userId, String code) {
        if (secret.length < 32) {
            throw new IllegalStateException(
                    "EMAIL_VERIFICATION_SECRET must contain at least 32 bytes");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(mac.doFinal((userId + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot hash verification code", exception);
        }
    }

    public boolean matches(Long userId, String code, String expected) {
        return MessageDigest.isEqual(
                hash(userId, code).getBytes(StandardCharsets.US_ASCII),
                expected.getBytes(StandardCharsets.US_ASCII));
    }
}
