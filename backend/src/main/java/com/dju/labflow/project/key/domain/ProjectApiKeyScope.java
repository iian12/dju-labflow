package com.dju.labflow.project.key.domain;

/** 향후 SDK 기능별 인가에 사용할 권한 목록. 현재 저장 및 인가 흐름에는 아직 연결되지 않았다. */
public enum ProjectApiKeyScope {
    RUN_WRITE,
    METRIC_WRITE,
    PARAMETER_WRITE,
    ARTIFACT_WRITE,
    DATASET_READ,
    DATASET_WRITE
}
