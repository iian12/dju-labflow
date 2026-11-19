package com.dju.labflow.sdk.request.infrastructure;

import com.dju.labflow.global.id.AssignedIdEntity;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.sdk.request.domain.SdkRequest;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 멱등성 결과를 실제 쓰기와 같은 DB 트랜잭션에 추가하는 불변 저장 모델. */
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "sdk_request_entity",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_sdk_request_project_id",
                        columnNames = {"project_id", "request_key"}))
public class SdkRequestEntity extends AssignedIdEntity {
    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(name = "request_key", nullable = false, updatable = false, length = 64)
    private String requestKey;

    @Column(nullable = false, updatable = false, length = 64)
    private String fingerprint;

    @Column(nullable = false, updatable = false)
    private Long resultRunId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public SdkRequestEntity(SdkRequest request) {
        id = request.id();
        projectId = request.projectId().value();
        requestKey = request.requestKey();
        fingerprint = request.fingerprint();
        resultRunId = request.resultRunId().value();
        createdAt = request.createdAt();
    }

    public SdkRequest toDomain() {
        return new SdkRequest(
                id,
                ProjectId.of(projectId),
                requestKey,
                fingerprint,
                RunId.of(resultRunId),
                createdAt);
    }
}
