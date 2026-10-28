package com.dju.labflow.project.project.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** 프로젝트 엔티티의 기본 JPA 저장 및 조회 연산을 제공한다. */
public interface ProjectJpaRepository extends JpaRepository<ProjectEntity, Long> {}
