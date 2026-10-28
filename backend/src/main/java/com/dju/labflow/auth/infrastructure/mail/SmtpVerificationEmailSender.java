package com.dju.labflow.auth.infrastructure.mail;

import com.dju.labflow.auth.application.verification.VerificationEmailSender;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** SMTP 설정은 환경변수로 공급하며 인증 코드를 로깅하지 않는다. */
@Component
public class SmtpVerificationEmailSender implements VerificationEmailSender {
    private final ObjectProvider<JavaMailSender> sender;
    private final String from;

    public SmtpVerificationEmailSender(
            ObjectProvider<JavaMailSender> sender,
            @Value("${auth.email-verification.from:}") String from) {
        this.sender = sender;
        this.from = from;
    }

    public void send(String email, String code) {
        if (from.isBlank()) throw new IllegalStateException("MAIL_FROM is required");
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("[LabFlow] 이메일 인증 코드");
        message.setText("인증 코드: " + code + "\n\n10분 이내에 가입 화면에 입력해 주세요.\n요청하지 않았다면 이 메일을 무시해 주세요.");
        sender.getObject().send(message);
    }
}
