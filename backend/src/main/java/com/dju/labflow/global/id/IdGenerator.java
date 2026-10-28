package com.dju.labflow.global.id;

/** 도메인이 특정 ID 생성 라이브러리에 의존하지 않도록 제공하는 ID 발급 계약. */
public interface IdGenerator {
    long nextId();
}
