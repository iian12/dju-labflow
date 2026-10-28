package com.dju.labflow.project.project.infrastructure.persistence;

import com.dju.labflow.project.project.domain.Project;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.project.project.domain.repository.ProjectRepository;
import com.dju.labflow.project.project.exception.ProjectNotFoundException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 프로젝트 도메인을 JPA에 연결하고 트랜잭션 안에서 기존 엔티티를 갱신한다. */
@Repository
public class ProjectRepositoryImpl implements ProjectRepository {

    private final ProjectJpaRepository projectJpaRepository;

    public ProjectRepositoryImpl(ProjectJpaRepository projectJpaRepository) {
        this.projectJpaRepository = projectJpaRepository;
    }

    @Override
    @Transactional
    public Project save(Project project) {
        Objects.requireNonNull(project, "Project must not be null");

        ProjectEntity savedEntity = projectJpaRepository.save(ProjectMapper.toEntity(project));
        return ProjectMapper.toDomain(savedEntity);
    }

    @Override
    @Transactional
    public void update(Project project) {
        ProjectEntity entity =
                projectJpaRepository
                        .findById(project.getId().value())
                        .orElseThrow(ProjectNotFoundException::new);
        entity.update(
                project.getName(),
                project.getDescription(),
                project.getVisibility(),
                project.getUpdatedAt());
    }

    @Override
    public Optional<Project> findById(ProjectId id) {
        if (id == null) return Optional.empty();

        return projectJpaRepository.findById(id.value()).map(ProjectMapper::toDomain);
    }
}
