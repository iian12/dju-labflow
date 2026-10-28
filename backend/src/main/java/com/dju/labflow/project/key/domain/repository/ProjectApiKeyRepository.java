package com.dju.labflow.project.key.domain.repository;

import com.dju.labflow.project.key.domain.ProjectApiKey;
import java.util.Optional;

/** 프로젝트 API 키 신규 저장과 해시 조회의 영속성 계약. */
public interface ProjectApiKeyRepository {

    /** 신규 키만 저장한다. 기존 키의 변경은 update를 사용한다. */
    ProjectApiKey save(ProjectApiKey projectApiKey);

    /** 기존 키의 이름/상태/폐기 및 사용 시각을 갱신한다. */
    void update(ProjectApiKey projectApiKey);

    Optional<ProjectApiKey> findByKeyHash(String keyHash);
}
