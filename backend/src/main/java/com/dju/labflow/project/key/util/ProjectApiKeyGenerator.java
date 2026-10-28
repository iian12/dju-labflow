package com.dju.labflow.project.key.util;

import com.dju.labflow.project.key.application.GeneratedProjectApiKey;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/** 구분 가능한 접두사와 256비트 비밀 난수를 결합해 프로젝트 API 키를 생성한다. */
@Component
public class ProjectApiKeyGenerator {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    public GeneratedProjectApiKey generate() {
        byte[] prefixBytes = new byte[6];
        byte[] secretBytes = new byte[32];

        SECURE_RANDOM.nextBytes(prefixBytes);
        SECURE_RANDOM.nextBytes(secretBytes);

        String prefix = ENCODER.encodeToString(prefixBytes);
        String secret = ENCODER.encodeToString(secretBytes);

        String rawKey = "lfp_" + prefix + "_" + secret;

        return new GeneratedProjectApiKey(rawKey, "lfp_" + prefix);
    }
}
