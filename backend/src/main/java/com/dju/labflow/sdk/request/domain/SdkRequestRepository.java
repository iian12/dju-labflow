package com.dju.labflow.sdk.request.domain;

import com.dju.labflow.project.project.domain.ProjectId;
import java.util.Optional;

/** 프로젝트 잠금을 획득한 트랜잭션 안에서만 멱등성 기록을 읽고 추가한다. */
public interface SdkRequestRepository {
    Optional<SdkRequest> find(ProjectId projectId, String requestKey);

    void save(SdkRequest request);
}
