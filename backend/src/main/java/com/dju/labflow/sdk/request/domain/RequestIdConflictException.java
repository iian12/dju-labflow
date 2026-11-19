package com.dju.labflow.sdk.request.domain;

/** 같은 프로젝트의 requestId가 다른 경로 또는 본문에 재사용된 경우. */
public class RequestIdConflictException extends RuntimeException {}
