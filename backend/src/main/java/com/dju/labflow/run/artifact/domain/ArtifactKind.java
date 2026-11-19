package com.dju.labflow.run.artifact.domain;

/** 파일 형식과 별도로 구분하는 실험 결과물의 용도. */
public enum ArtifactKind {
    MODEL,
    CHECKPOINT,
    REPORT,
    PREDICTION,
    OTHER
}
