package com.dju.labflow.project.project.domain;

/** 프로젝트 공개 범위의 도메인 값. 실제 리소스 조회 시 별도의 인가 검사가 필요하다. */
public enum ProjectVisibility {
    PRIVATE,
    TEAM,
    PUBLIC
}
