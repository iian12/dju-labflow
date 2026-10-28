package com.dju.labflow.global;

// 공통 응답 모듈
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/** 필터와 MVC가 공유하는 오류 응답. 검증 실패 시 필드별 사유를 함께 제공한다. */
@Schema(description = "인증 및 API 오류 응답. 입력값이나 비밀번호 원문은 포함하지 않습니다.")
public record SecurityErrorResponse(
        @Schema(example = "INVALID_INPUT") String code,
        @Schema(example = "Request validation failed") String message,
        @Schema(description = "검증에 실패한 필드와 사유") Map<String, String> errors) {
    public SecurityErrorResponse(String code, String message) {
        this(code, message, Map.of());
    }
}
