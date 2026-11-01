package com.dju.labflow.project.project.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 프로젝트 엔티티의 기본 JPA 저장 및 조회 연산을 제공한다. */
public interface ProjectJpaRepository extends JpaRepository<ProjectEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProjectEntity p where p.id = :id")
    Optional<ProjectEntity> findByIdForUpdate(@Param("id") Long id);

    Page<ProjectEntity> findByCreatedBy(Long ownerId, Pageable pageable);
}
