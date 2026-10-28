package com.dju.labflow.project.project.domain.repository;

import com.dju.labflow.project.project.domain.Project;
import com.dju.labflow.project.project.domain.ProjectId;
import java.util.Optional;

/** 프로젝트 신규 저장, 기존 데이터 수정 및 식별자 조회의 영속성 계약. */
public interface ProjectRepository {

    /** 신규 프로젝트 INSERT 전용. 기존 프로젝트의 변경은 update를 사용한다. */
    Project save(Project project);

    void update(Project project);

    Optional<Project> findById(ProjectId id);
}
