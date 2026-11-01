package com.dju.labflow.run.text.infrastructure.persistence;

import com.dju.labflow.global.id.AssignedIdEntity;
import com.dju.labflow.run.text.domain.*;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

/** 모델 입력·출력·정답 등의 텍스트 샘플. 실행 메시지와 분리한다.의 추가 전용 저장 모델. 기존 기록은 수정하지 않는다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "run_text_entity",
        indexes = @Index(name = "idx_run_text_time", columnList = "run_id,recorded_at,id"))
public class RunTextEntity extends AssignedIdEntity {
    @Column(name = "run_id", nullable = false, updatable = false)
    private Long runId;

    @Column(name = "text_key", nullable = false, updatable = false, length = 100)
    private String key;

    @Lob
    // Without an explicit type MariaDB may choose TINYTEXT (255 bytes) for a default-sized LOB.
    @Column(nullable = false, updatable = false, columnDefinition = "LONGTEXT")
    private String text;

    @Column(updatable = false)
    private Long step;

    @Column(nullable = false, updatable = false)
    private Instant recordedAt;

    @Builder
    public RunTextEntity(
            Long id, Long runId, String key, String text, Long step, Instant recordedAt) {
        this.id = id;
        this.runId = runId;
        this.key = key;
        this.text = text;
        this.step = step;
        this.recordedAt = recordedAt;
    }
}
