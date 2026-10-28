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
    private final EmailVerificationRepository emailVerificationRepository;
    private final UserRepository userRepository;
    private final EmailVerificationCodeGenerator emailVerificationCodeGenerator;
    private final VerificationCodeHasher verificationCodeHasher;
    private final Clock clock;
    private final ApplicationEventPublisher applicationEventPublisher;

    public EmailVerificationService(
            EmailVerificationRepository emailVerificationRepository,
            UserRepository userRepository,
            EmailVerificationCodeGenerator emailVerificationCodeGenerator,
            VerificationCodeHasher verificationCodeHasher,
            Clock clock,
            ApplicationEventPublisher applicationEventPublisher) {
        this.emailVerificationRepository = emailVerificationRepository;
        this.userRepository = userRepository;
        this.emailVerificationCodeGenerator = emailVerificationCodeGenerator;
        this.verificationCodeHasher = verificationCodeHasher;
        this.clock = clock;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    public void issue(User user) {
        String code = emailVerificationCodeGenerator.generate();
        emailVerificationRepository.save(
                EmailVerification.issue(
                        user.getId().value(),
                        verificationCodeHasher.hash(user.getId().value(), code),
                        clock.instant()));
        applicationEventPublisher.publishEvent(
                new VerificationMailRequested(user.getEmail(), code));
    }

    /** 미가입/이미 인증/제한 초과 여부를 응답으로 노출하지 않는다. */
    public void resend(String email) {
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || user.getAccountStatus() != AccountStatus.PENDING_VERIFICATION) return;
        EmailVerification token =
                emailVerificationRepository.findForUpdate(user.getId().value()).orElse(null);
        if (token == null || !token.canResend(clock.instant())) return;
        String code = emailVerificationCodeGenerator.generate();
        token.resend(verificationCodeHasher.hash(user.getId().value(), code), clock.instant());
        emailVerificationRepository.update(token);
        applicationEventPublisher.publishEvent(new VerificationMailRequested(email, code));
    }

    @Transactional(noRollbackFor = InvalidVerificationCodeException.class)
    public void verify(String email, String input) {
        User user =
                userRepository
                        .findByEmail(email)
                        .orElseThrow(InvalidVerificationCodeException::new);
        EmailVerification token =
                emailVerificationRepository
                        .findForUpdate(user.getId().value())
                        .orElseThrow(InvalidVerificationCodeException::new);
        if (user.getAccountStatus() != AccountStatus.PENDING_VERIFICATION
                || !token.usable(clock.instant())) {
            throw new InvalidVerificationCodeException();
        }
        String code;
        try {
            code = emailVerificationCodeGenerator.normalize(input);
        } catch (IllegalArgumentException exception) {
            token.fail();
            emailVerificationRepository.update(token);
            throw new InvalidVerificationCodeException();
        }
        if (!verificationCodeHasher.matches(user.getId().value(), code, token.getCodeHash())) {
            token.fail();
            emailVerificationRepository.update(token);
            throw new InvalidVerificationCodeException();
        }
        user.activate();
        userRepository.update(user);
        token.consume();
        emailVerificationRepository.update(token);
    }
}
