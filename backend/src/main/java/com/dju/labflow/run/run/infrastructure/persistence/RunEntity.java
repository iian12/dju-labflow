package com.dju.labflow.run.run.infrastructure.persistence;

import com.dju.labflow.global.id.AssignedIdEntity;
import com.dju.labflow.run.run.domain.RunStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.*;

/** 실행 저장 모델. 소속/시작 시각은 불변이며 종료 상태만 갱신한다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "run_entity",
        indexes = @Index(name = "idx_run_project_time", columnList = "project_id,started_at,id"))
public class RunEntity extends AssignedIdEntity {
    @Version private Long version;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(nullable = false, updatable = false)
    private Long createdBy;

    @Column(nullable = false, updatable = false, length = 100)
    private String name;

    @ElementCollection
    @CollectionTable(name = "run_metadata", joinColumns = @JoinColumn(name = "run_id"))
    @MapKeyColumn(name = "metadata_key", length = 100)
    @Column(name = "metadata_value", nullable = false, length = 2000)
    private Map<String, String> metadata = new HashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RunStatus status;

    @Column(nullable = false, updatable = false)
    private Instant startedAt;

    private Instant endedAt;

    @Column(length = 4000)
    private String error;

    @Builder
    public RunEntity(
            Long id,
            Long projectId,
            Long createdBy,
            String name,
            Map<String, String> metadata,
            RunStatus status,
            Instant startedAt,
            Instant endedAt,
            String error) {
        this.id = id;
        this.projectId = projectId;
        this.createdBy = createdBy;
        this.name = name;
        this.metadata = new HashMap<>(metadata);
        this.status = status;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.error = error;
    }

    public void update(RunStatus status, Instant endedAt, String error) {
        this.status = status;
        this.endedAt = endedAt;
        this.error = error;
    }
}
