package com.dju.labflow.run.log.infrastructure.persistence;

import com.dju.labflow.global.id.AssignedIdEntity;
import com.dju.labflow.run.log.domain.*;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

/** 실행 중 발생한 텍스트 메시지. 원문과 수집 출처를 보존한다.의 추가 전용 저장 모델. 기존 기록은 수정하지 않는다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "run_log_entity",
        indexes = @Index(name = "idx_run_log_time", columnList = "run_id,recorded_at,id"))
public class RunLogEntity extends AssignedIdEntity {
    @Column(name = "run_id", nullable = false, updatable = false)
    private Long runId;

    @Lob
    // Match the API limit and deployment DDL instead of MariaDB's default-sized TINYTEXT.
    @Column(nullable = false, updatable = false, columnDefinition = "LONGTEXT")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private RunLogLevel level;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private RunLogSource source;

    @Column(nullable = false, updatable = false)
    private Instant recordedAt;

    @Builder
    public RunLogEntity(
            Long id,
            Long runId,
            String message,
            RunLogLevel level,
            RunLogSource source,
            Instant recordedAt) {
        this.id = id;
        this.runId = runId;
        this.message = message;
        this.level = level;
        this.source = source;
        this.recordedAt = recordedAt;
    }
}
