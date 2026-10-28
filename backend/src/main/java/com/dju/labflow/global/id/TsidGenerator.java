package com.dju.labflow.global.id;

import io.hypersistence.tsid.TSID;
import org.springframework.stereotype.Component;

/** TSID 라이브러리로 시간 정렬 가능한 64비트 식별자를 발급한다. */
@Component
public class TsidGenerator implements IdGenerator {
    @Override
    public long nextId() {
        return TSID.fast().toLong();
    }
}
