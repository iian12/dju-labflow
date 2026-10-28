package com.dju.labflow.global.validation;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

/** 컨트롤러를 거치지 않는 서비스 호출에도 Bean Validation 규칙을 적용한다. */
@Component
public class RequestValidator {
    private final Validator validator;

    public RequestValidator(Validator validator) {
        this.validator = validator;
    }

    public void validate(Object request) {
        InputValidation.required(request, "Request");
        var violations = validator.validate(request);
        if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
    }
}
