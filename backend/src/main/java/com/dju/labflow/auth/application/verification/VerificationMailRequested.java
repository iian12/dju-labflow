package com.dju.labflow.auth.application.verification;

/** 커밋 후 메일 발송용 메모리 이벤트. 코드가 로그에 노출되지 않도록 한다. */
public record VerificationMailRequested(String email, String code) {
    @Override
    public String toString() {
        return "VerificationMailRequested[redacted]";
    }
}
