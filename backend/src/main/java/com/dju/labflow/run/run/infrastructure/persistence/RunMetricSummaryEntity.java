package com.dju.labflow.run.run.infrastructure.persistence;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import lombok.*;

/** 원본 메트릭과 같은 트랜잭션에서 갱신하는 지표별 조회 모델. */
@Entity
@com.dju.labflow.global.schema.SchemaSince(2)
@Table(name = "run_metric_summary")
@IdClass(RunMetricSummaryEntity.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RunMetricSummaryEntity {
    @Id
    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Id
    @Column(name = "metric_key", nullable = false, length = 100)
    private String seriesKey;

    @Column(nullable = false)
    private Long latestMetricId;

    @Column(nullable = false)
    private Instant latestRecordedAt;

    private Long maxStep;

    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private Long runId;
        private String seriesKey;
    }
}
