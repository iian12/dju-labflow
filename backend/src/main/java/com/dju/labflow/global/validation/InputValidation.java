package com.dju.labflow.global.validation;

import java.nio.charset.StandardCharsets;

/** HTTP 외부에서 도메인을 생성하거나 변경해도 지켜야 하는 기본 입력 규칙. */
public final class InputValidation {
    private InputValidation() {}

    public static <T> T required(T value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    public static String text(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        optionalText(value, field, maxLength);
        return value;
    }

    public static String optionalText(String value, String field, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        return value;
    }

    /** BCrypt의 입력 한도는 문자 수가 아닌 UTF-8 바이트 수다. 원문은 변형하지 않는다. */
    public static void password(String value) {
        text(value, "Password", 72);
        if (value.length() < 8 || value.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException(
                    "Password must be at least 8 characters and at most 72 UTF-8 bytes");
        }
    }
}
