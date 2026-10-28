package com.dju.labflow.auth.infrastructure.mail;

import com.dju.labflow.auth.application.verification.VerificationEmailSender;
import com.dju.labflow.auth.application.verification.VerificationMailRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** DB 커밋 후 발송한다. 실패 시 가입 상태를 유지하여 사용자가 재발송할 수 있다. */
@Component
public class VerificationMailListener {
    private static final Logger log = LoggerFactory.getLogger(VerificationMailListener.class);
    private final VerificationEmailSender sender;

    public VerificationMailListener(VerificationEmailSender sender) {
        this.sender = sender;
    }

    @TransactionalEventListener
    public void send(VerificationMailRequested event) {
        try {
            sender.send(event.email(), event.code());
        } catch (RuntimeException exception) {
            // SMTP 예외에는 수신자나 본문이 포함될 수 있으므로 예외 원문을 남기지 않는다.
            log.error(
                    "Verification email delivery failed; check SMTP configuration and availability");
        }
    }
}
