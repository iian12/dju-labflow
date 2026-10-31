package com.dju.labflow.run.run.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 실행 조회와 쓰기 작업의 부모 행 잠금. */
public interface RunJpaRepository extends JpaRepository<RunEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RunEntity r where r.id = :id")
    Optional<RunEntity> findByIdForUpdate(@Param("id") Long id);

    List<RunEntity> findByProjectIdOrderByStartedAtDescIdDesc(Long projectId, Pageable pageable);
}
