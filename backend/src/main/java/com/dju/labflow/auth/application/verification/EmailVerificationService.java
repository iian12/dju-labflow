package com.dju.labflow.auth.application.verification;

import com.dju.labflow.auth.domain.EmailVerification;
import com.dju.labflow.auth.domain.EmailVerificationRepository;
import com.dju.labflow.auth.domain.exception.InvalidVerificationCodeException;
import com.dju.labflow.user.domain.AccountStatus;
import com.dju.labflow.user.domain.User;
import com.dju.labflow.user.domain.repository.UserRepository;
import java.time.Clock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 코드 발급, 재발송 제한, 일회성 계정 활성화를 조정한다. */
@Service
@Transactional
public class EmailVerificationService {
    private final EmailVerificationRepository tokens;
    private final UserRepository users;
    private final EmailVerificationCodeGenerator generator;
    private final VerificationCodeHasher hasher;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public EmailVerificationService(
            EmailVerificationRepository tokens,
            UserRepository users,
            EmailVerificationCodeGenerator generator,
            VerificationCodeHasher hasher,
            Clock clock,
            ApplicationEventPublisher events) {
        this.tokens = tokens;
        this.users = users;
        this.generator = generator;
        this.hasher = hasher;
        this.clock = clock;
        this.events = events;
    }

    public void issue(User user) {
        String code = generator.generate();
        tokens.save(
                EmailVerification.issue(
                        user.getId().value(),
                        hasher.hash(user.getId().value(), code),
                        clock.instant()));
        events.publishEvent(new VerificationMailRequested(user.getEmail(), code));
    }

    /** 미가입/이미 인증/제한 초과 여부를 응답으로 노출하지 않는다. */
    public void resend(String email) {
        User user = users.findByEmail(email).orElse(null);
        if (user == null || user.getAccountStatus() != AccountStatus.PENDING_VERIFICATION) return;
        EmailVerification token = tokens.findForUpdate(user.getId().value()).orElse(null);
        if (token == null || !token.canResend(clock.instant())) return;
        String code = generator.generate();
        token.resend(hasher.hash(user.getId().value(), code), clock.instant());
        tokens.update(token);
        events.publishEvent(new VerificationMailRequested(email, code));
    }

    @Transactional(noRollbackFor = InvalidVerificationCodeException.class)
    public void verify(String email, String input) {
        User user = users.findByEmail(email).orElseThrow(InvalidVerificationCodeException::new);
        EmailVerification token =
                tokens.findForUpdate(user.getId().value())
                        .orElseThrow(InvalidVerificationCodeException::new);
        if (user.getAccountStatus() != AccountStatus.PENDING_VERIFICATION
                || !token.usable(clock.instant())) {
            throw new InvalidVerificationCodeException();
        }
        String code;
        try {
            code = generator.normalize(input);
        } catch (IllegalArgumentException exception) {
            token.fail();
            tokens.update(token);
            throw new InvalidVerificationCodeException();
        }
        if (!hasher.matches(user.getId().value(), code, token.getCodeHash())) {
            token.fail();
            tokens.update(token);
            throw new InvalidVerificationCodeException();
        }
        user.activate();
        users.update(user);
        token.consume();
        tokens.update(token);
    }
}
