package com.dju.labflow.project.project.infrastructure.persistence;

import com.dju.labflow.project.project.domain.Project;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.project.project.domain.repository.ProjectRepository;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.Optional;

@Repository
public class ProjectRepositoryImpl implements ProjectRepository {

    private final ProjectJpaRepository jpaRepository;

    public  ProjectRepositoryImpl(ProjectJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Project save(Project project) {
        Objects.requireNonNull(project, "Project must not be null");

        ProjectEntity savedEntity = jpaRepository.save(ProjectMapper.toEntity(project));
        return ProjectMapper.toDomain(savedEntity);

    }

    @Override
    public void update(Project project) {
        ProjectEntity entity = jpaRepository.findById(project.getId().value())
                .orElseThrow(() -> new IllegalArgumentException("Project not found with id: " + project.getId().value()));
    }

    @Override
    public Optional<Project> findById(ProjectId id) {
        if (id == null) return Optional.empty();

        return jpaRepository.findById(id.value())
                .map(ProjectMapper::toDomain);
    }
}
