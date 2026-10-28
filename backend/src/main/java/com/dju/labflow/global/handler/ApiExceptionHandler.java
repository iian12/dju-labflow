package com.dju.labflow.global.handler;

import com.dju.labflow.auth.domain.exception.RefreshTokenException;
import com.dju.labflow.global.SecurityErrorResponse;
import com.dju.labflow.project.key.exception.ProjectApiKeyNotFoundException;
import com.dju.labflow.project.project.exception.ProjectNotFoundException;
import com.dju.labflow.user.exception.DuplicateEmailException;
import com.dju.labflow.user.exception.UserNotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** MVC와 서비스의 예상 가능한 실패를 일관된 HTTP 상태/오류 본문으로 변환한다. */
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<SecurityErrorResponse> invalidBody(
            MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception
                .getBindingResult()
                .getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(
                        new SecurityErrorResponse(
                                "INVALID_INPUT", "Request validation failed", errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<SecurityErrorResponse> invalidCommand(
            ConstraintViolationException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception
                .getConstraintViolations()
                .forEach(
                        error ->
                                errors.putIfAbsent(
                                        error.getPropertyPath().toString(), error.getMessage()));
        return ResponseEntity.badRequest()
                .body(
                        new SecurityErrorResponse(
                                "INVALID_INPUT", "Request validation failed", errors));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<SecurityErrorResponse> invalidParameters(
            HandlerMethodValidationException exception) {
        if (exception.isForReturnValue()) {
            return error(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "INTERNAL_ERROR",
                    "Response validation failed");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        exception
                .getParameterValidationResults()
                .forEach(
                        result -> {
                            if (result instanceof ParameterErrors parameterErrors) {
                                parameterErrors
                                        .getFieldErrors()
                                        .forEach(
                                                field ->
                                                        errors.putIfAbsent(
                                                                field.getField(),
                                                                field.getDefaultMessage()));
                            } else {
                                String parameter = result.getMethodParameter().getParameterName();
                                result.getResolvableErrors()
                                        .forEach(
                                                error ->
                                                        errors.putIfAbsent(
                                                                parameter == null
                                                                        ? "parameter"
                                                                        : parameter,
                                                                error.getDefaultMessage()));
                            }
                        });
        return ResponseEntity.badRequest()
                .body(
                        new SecurityErrorResponse(
                                "INVALID_INPUT", "Request validation failed", errors));
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<SecurityErrorResponse> malformedRequest(Exception exception) {
        // 파서 예외에는 입력 원문이 들어갈 수 있으므로 그대로 반환하지 않는다.
        return error(
                HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Malformed request or invalid parameter");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<SecurityErrorResponse> invalidInput(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Invalid request value");
    }

    @ExceptionHandler(ProjectNotFoundException.class)
    public ResponseEntity<SecurityErrorResponse> projectNotFound(
            ProjectNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "Project not found");
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<SecurityErrorResponse> userNotFound(UserNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found");
    }

    @ExceptionHandler(ProjectApiKeyNotFoundException.class)
    public ResponseEntity<SecurityErrorResponse> apiKeyNotFound(
            ProjectApiKeyNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "API_KEY_NOT_FOUND", "Project API key not found");
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<SecurityErrorResponse> duplicateEmail(DuplicateEmailException exception) {
        return error(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "Email already exists");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<SecurityErrorResponse> dataConflict(
            DataIntegrityViolationException exception) {
        // 사전 중복 검사 이후 동시에 INSERT된 경우도 500 대신 409로 처리한다.
        return error(HttpStatus.CONFLICT, "DATA_CONFLICT", "Request conflicts with existing data");
    }

    @ExceptionHandler({AuthenticationException.class, RefreshTokenException.class})
    public ResponseEntity<SecurityErrorResponse> authenticationFailed(RuntimeException exception) {
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication failed");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<SecurityErrorResponse> forbidden(AccessDeniedException exception) {
        return error(
                HttpStatus.FORBIDDEN,
                "FORBIDDEN",
                "You do not have permission to access this resource");
    }

    private ResponseEntity<SecurityErrorResponse> error(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new SecurityErrorResponse(code, message));
    }
}
