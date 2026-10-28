package com.dju.labflow.project.key.application;

/** API 키 원문과 관리 화면용 접두사. 원문은 발급 시에만 전달한다. */
public record GeneratedProjectApiKey(String rawKey, String keyPrefix) {}
