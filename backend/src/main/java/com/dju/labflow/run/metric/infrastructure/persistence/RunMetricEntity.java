package com.dju.labflow.run.metric.infrastructure.persistence;

import com.dju.labflow.global.id.AssignedIdEntity;
import com.dju.labflow.run.metric.domain.*;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

/** 한 실행의 숫자 지표 한 점. 문자열과 비유한 수는 허용하지 않는다.의 추가 전용 저장 모델. 기존 기록은 수정하지 않는다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "run_metric_entity",
        indexes = @Index(name = "idx_run_metric_time", columnList = "run_id,recorded_at,id"))
public class RunMetricEntity extends AssignedIdEntity {
    @Column(name = "run_id", nullable = false, updatable = false)
    private Long runId;

    @Column(name = "metric_key", nullable = false, updatable = false, length = 100)
    private String key;

    @Column(name = "metric_value", nullable = false, updatable = false)
    private Double value;

    @Column(updatable = false)
    private Integer epoch;

    @Column(updatable = false)
    private Long step;

    @Column(updatable = false)
    private Double elapsedTime;

    @Column(nullable = false, updatable = false)
    private Instant recordedAt;

    @Builder
    public RunMetricEntity(
            Long id,
            Long runId,
            String key,
            Double value,
            Integer epoch,
            Long step,
            Double elapsedTime,
            Instant recordedAt) {
        this.id = id;
        this.runId = runId;
        this.key = key;
        this.value = value;
        this.epoch = epoch;
        this.step = step;
        this.elapsedTime = elapsedTime;
        this.recordedAt = recordedAt;
    }
}
