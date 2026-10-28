package com.dju.labflow.global.id;

import io.hypersistence.tsid.TSID;
import org.springframework.stereotype.Component;

@Component
public class TsidGenerator implements IdGenerator {
    @Override
    public long nextId() {
        return TSID.fast().toLong();
    }
}
