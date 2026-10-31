package com.dju.labflow.run.systemmetric.domain;

import com.dju.labflow.global.validation.InputValidation;
import com.dju.labflow.run.run.domain.RunValidation;

/** GPU 번호, 사용률(0~100), 메모리 사용량/전체량(바이트)의 값 객체. 미수집 수치는 null이다. */
public record GpuMetric(Integer index, Double utilization, Long memoryUsed, Long memoryTotal) {
    public GpuMetric {
        RunValidation.nonNegative(InputValidation.required(index, "GPU index"), "GPU index");
        RunValidation.percentage(utilization, "GPU utilization");
        RunValidation.memory(memoryUsed, memoryTotal);
    }
}
