package com.dju.labflow.run.parameter.infrastructure.persistence;

import com.dju.labflow.global.id.AssignedIdEntity;
import com.dju.labflow.run.parameter.domain.*;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

/** 실행에 한 번 정한 설정 값. 같은 키의 값은 변경하지 않는다.의 추가 전용 저장 모델. 기존 기록은 수정하지 않는다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "run_parameter_entity",
        indexes = @Index(name = "idx_run_parameter_time", columnList = "run_id"),
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_run_parameter_key",
                        columnNames = {"run_id", "parameter_key"}))
public class RunParameterEntity extends AssignedIdEntity {
    @Column(name = "run_id", nullable = false, updatable = false)
    private Long runId;

    @Column(name = "parameter_key", nullable = false, updatable = false, length = 100)
    private String key;

    @Column(name = "parameter_value", nullable = false, updatable = false, length = 2000)
    private String value;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Builder
    public RunParameterEntity(Long id, Long runId, String key, String value, Instant createdAt) {
        this.id = id;
        this.runId = runId;
        this.key = key;
        this.value = value;
        this.createdAt = createdAt;
    }
}
