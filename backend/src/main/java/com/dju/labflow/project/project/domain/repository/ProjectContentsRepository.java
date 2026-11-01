package com.dju.labflow.project.project.domain.repository;

import com.dju.labflow.project.project.domain.ProjectId;

/** 프로젝트 삭제 트랜잭션에서 키와 실행의 모든 종속 기록을 정리한다. */
public interface ProjectContentsRepository {
    /** 프로젝트 행 잠금을 보유한 서비스 트랜잭션 안에서만 호출한다. */
    void deleteByProjectId(ProjectId projectId);
}
