package com.dju.labflow.run.run.application;

import jakarta.validation.constraints.*;

/** 사용자용 프로젝트 실행 목록 요청. */
public record RunListQuery(@PositiveOrZero int page, @Min(1) @Max(100) int size) {}
