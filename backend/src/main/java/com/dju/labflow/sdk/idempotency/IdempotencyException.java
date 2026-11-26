package com.dju.labflow.sdk.idempotency;

/** HTTP 경계에서 반환할 공개 오류. 키나 요청 본문은 예외에 포함하지 않는다. */
public class IdempotencyException extends RuntimeException {
    private final int status;
    private final String code;

    public IdempotencyException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static IdempotencyException processing() {
        return new IdempotencyException(
                409, "IDEMPOTENT_REQUEST_PROCESSING", "Previous idempotent request is processing");
    }

    public static IdempotencyException mismatch() {
        return new IdempotencyException(
                422,
                "IDEMPOTENCY_KEY_REUSED",
                "Idempotency key was used with different request content");
    }
}
