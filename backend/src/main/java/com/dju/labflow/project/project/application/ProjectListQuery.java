package com.dju.labflow.project.project.application;

import jakarta.validation.constraints.*;

/** 개인 프로젝트 목록의 페이지 범위. */
public record ProjectListQuery(@PositiveOrZero int page, @Min(1) @Max(100) int size) {}
