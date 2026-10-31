package com.dju.labflow.run.systemmetric.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.*;

/** 시스템 스냅샷에 종속된 GPU 측정값의 저장 모델. */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GpuMetricEmbeddable {
    @Column(name = "gpu_index", nullable = false)
    private Integer gpuIndex;

    private Double utilization;
    private Long memoryUsed;
    private Long memoryTotal;

    public GpuMetricEmbeddable(
            Integer gpuIndex, Double utilization, Long memoryUsed, Long memoryTotal) {
        this.gpuIndex = gpuIndex;
        this.utilization = utilization;
        this.memoryUsed = memoryUsed;
        this.memoryTotal = memoryTotal;
    }
}
