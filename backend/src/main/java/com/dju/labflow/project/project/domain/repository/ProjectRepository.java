package com.dju.labflow.project.project.domain.repository;

import com.dju.labflow.project.project.domain.Project;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.project.project.domain.ProjectPage;
import com.dju.labflow.user.domain.UserId;
import java.util.Optional;

/** 프로젝트 신규 저장, 기존 데이터 수정 및 식별자 조회의 영속성 계약. */
public interface ProjectRepository {

    /** 신규 프로젝트 INSERT 전용. 기존 프로젝트의 변경은 update를 사용한다. */
    Project save(Project project);

    /** 조회 시점의 버전이 일치할 때만 갱신한다. 저장 후 재사용하려면 다시 조회한다. */
    void update(Project project);

    Optional<Project> findById(ProjectId id);

    /** 서비스 쓰기 트랜잭션 안에서 프로젝트 쓰기와 종속 데이터 생성을 직렬화한다. */
    Optional<Project> findByIdForUpdate(ProjectId id);

    ProjectPage findByCreatedBy(UserId ownerId, int page, int size);

    /** 종속 기록을 삭제한 뒤 같은 버전의 프로젝트만 삭제한다. */
    void delete(Project project);
}
