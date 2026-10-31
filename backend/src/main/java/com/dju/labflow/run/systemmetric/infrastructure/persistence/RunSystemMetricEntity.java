package com.dju.labflow.run.systemmetric.infrastructure.persistence;

import com.dju.labflow.global.id.AssignedIdEntity;
import com.dju.labflow.run.systemmetric.domain.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.*;

/** CPU/RAM/GPU 스냅샷의 추가 전용 저장 모델. 기존 기록은 수정하지 않는다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "run_system_metric_entity",
        indexes = @Index(name = "idx_run_systemmetric_time", columnList = "run_id,recorded_at,id"))
public class RunSystemMetricEntity extends AssignedIdEntity {
    @Column(name = "run_id", nullable = false, updatable = false)
    private Long runId;

    @Column(updatable = false)
    private Double cpuUtilization;

    @Column(updatable = false)
    private Long ramUsed;

    @Column(updatable = false)
    private Long ramTotal;

    @ElementCollection
    @CollectionTable(
            name = "run_system_metric_gpu",
            joinColumns = @JoinColumn(name = "system_metric_id"))
    @OrderBy("gpuIndex ASC")
    private List<GpuMetricEmbeddable> gpus;

    @Column(updatable = false)
    private Double elapsedTime;

    @Column(nullable = false, updatable = false)
    private Instant recordedAt;

    @Builder
    public RunSystemMetricEntity(
            Long id,
            Long runId,
            Double cpuUtilization,
            Long ramUsed,
            Long ramTotal,
            List<GpuMetricEmbeddable> gpus,
            Double elapsedTime,
            Instant recordedAt) {
        this.id = id;
        this.runId = runId;
        this.cpuUtilization = cpuUtilization;
        this.ramUsed = ramUsed;
        this.ramTotal = ramTotal;
        this.gpus = new ArrayList<>(gpus);
        this.elapsedTime = elapsedTime;
        this.recordedAt = recordedAt;
    }
}
