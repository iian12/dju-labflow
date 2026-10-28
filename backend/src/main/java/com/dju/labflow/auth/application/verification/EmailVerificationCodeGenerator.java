package com.dju.labflow.auth.application.verification;

import java.security.SecureRandom;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** 혼동하기 쉬운 문자를 제외한 이메일 인증 코드를 생성한다. */
@Component
public class EmailVerificationCodeGenerator {

    private static final int CODE_LENGTH = 6;

    // 영문 I, L, O와 숫자 0, 1을 제외한다.
    private static final String LETTERS = "ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final String DIGITS = "23456789";
    private static final String ALPHABET = LETTERS + DIGITS;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        while (true) {
            char[] code = new char[CODE_LENGTH];
            boolean hasLetter = false;
            boolean hasDigit = false;

            for (int i = 0; i < CODE_LENGTH; i++) {
                char character = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
                code[i] = character;

                if (DIGITS.indexOf(character) >= 0) {
                    hasDigit = true;
                } else {
                    hasLetter = true;
                }
            }

            // 두 종류가 모두 포함된 경우에만 반환한다.
            if (hasLetter && hasDigit) {
                return new String(code);
            }
        }
    }

    /** 입력을 정규화하고 생성 규칙과 일치하는지 검사한다. */
    public String normalize(String input) {
        if (input == null) {
            throw new IllegalArgumentException("인증 코드를 입력해 주세요.");
        }

        String code = input.strip().toUpperCase(Locale.ROOT);

        if (code.length() != CODE_LENGTH) {
            throw new IllegalArgumentException("인증 코드는 6자리여야 합니다.");
        }

        boolean hasLetter = false;
        boolean hasDigit = false;

        for (int i = 0; i < code.length(); i++) {
            char character = code.charAt(i);

            if (DIGITS.indexOf(character) >= 0) {
                hasDigit = true;
            } else if (LETTERS.indexOf(character) >= 0) {
                hasLetter = true;
            } else {
                throw new IllegalArgumentException("인증 코드 형식이 올바르지 않습니다.");
            }
        }

        if (!hasLetter || !hasDigit) {
            throw new IllegalArgumentException("인증 코드 형식이 올바르지 않습니다.");
        }

        return code;
    }
}
