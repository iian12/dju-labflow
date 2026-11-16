package com.dju.labflow.run.artifact.domain;

/** 예약된 파일은 검증이 끝난 뒤에만 READY로 공개한다. */
public enum ArtifactStatus {
    UPLOADING,
    READY,
    FAILED,
    DELETING
}
