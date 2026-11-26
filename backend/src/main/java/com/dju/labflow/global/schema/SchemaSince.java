package com.dju.labflow.global.schema;

import java.lang.annotation.*;

/** 최초 도입 버전. 기존 DB 편입 시 아직 적용되지 않은 조회 모델을 요구하지 않는다. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SchemaSince {
    int value();
}
