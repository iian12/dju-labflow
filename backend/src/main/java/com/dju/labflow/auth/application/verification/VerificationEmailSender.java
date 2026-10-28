package com.dju.labflow.auth.application.verification;

/** 인증 코드 원문을 메일 전송 경계로만 전달한다. */
public interface VerificationEmailSender {
    void send(String email, String code);
}
