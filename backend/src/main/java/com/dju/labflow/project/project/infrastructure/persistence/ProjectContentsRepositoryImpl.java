package com.dju.labflow.project.project.infrastructure.persistence;

import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.project.project.domain.repository.ProjectContentsRepository;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 컬렉션 FK를 자식부터 정리하고 키/실행/기록을 같은 트랜잭션에서 물리 삭제한다. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class ProjectContentsRepositoryImpl implements ProjectContentsRepository {
    private final EntityManager entityManager;

    public ProjectContentsRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public void deleteByProjectId(ProjectId projectId) {
        Objects.requireNonNull(projectId, "Project ID must not be null");
        entityManager.flush();
        String runs = "select id from run_entity where project_id = :projectId";
        execute(
                "delete from run_system_metric_gpu where system_metric_id in (select id from run_system_metric_entity where run_id in ("
                        + runs
                        + "))",
                projectId);
        for (String table :
                new String[] {
                    "run_parameter_entity",
                    "run_metric_entity",
                    "run_log_entity",
                    "run_text_entity",
                    "run_system_metric_entity"
                }) execute("delete from " + table + " where run_id in (" + runs + ")", projectId);
        execute("delete from run_metadata where run_id in (" + runs + ")", projectId);
        execute("delete from run_entity where project_id = :projectId", projectId);
        execute("delete from project_api_key_entity where project_id = :projectId", projectId);
        // 네이티브 삭제 결과와 영속성 컨텍스트의 이전 객체가 섞이지 않게 한다. 행 잠금은 커밋까지 유지된다.
        entityManager.clear();
    }

    private void execute(String sql, ProjectId projectId) {
        entityManager
                .createNativeQuery(sql)
                .setParameter("projectId", projectId.value())
                .executeUpdate();
    }
}
