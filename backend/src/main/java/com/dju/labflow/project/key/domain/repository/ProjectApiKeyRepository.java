package com.dju.labflow.project.key.domain.repository;

import com.dju.labflow.project.key.domain.ProjectApiKey;
import com.dju.labflow.project.key.domain.ProjectApiKeyId;
import java.time.Instant;
import java.util.Optional;

/** 프로젝트 API 키 신규 저장과 해시 조회의 영속성 계약. */
public interface ProjectApiKeyRepository {

    /** 신규 키만 저장한다. 기존 키의 변경은 update를 사용한다. */
    ProjectApiKey save(ProjectApiKey projectApiKey);

    /** 조회 시점의 버전이 일치할 때만 기존 키를 갱신한다. 저장 후 재사용하려면 다시 조회한다. */
    void update(ProjectApiKey projectApiKey);

    Optional<ProjectApiKey> findByKeyHash(String keyHash);

    /** 활성 키의 사용 시각만 단조 증가시킨다. 폐기되었거나 사라진 키는 false를 반환한다. */
    boolean recordUsage(ProjectApiKeyId id, Instant usedAt);
}
