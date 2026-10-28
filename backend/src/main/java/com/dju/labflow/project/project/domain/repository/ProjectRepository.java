package com.dju.labflow.project.project.domain.repository;

import com.dju.labflow.project.project.domain.Project;
import com.dju.labflow.project.project.domain.ProjectId;

import java.util.Optional;

public interface ProjectRepository {

    Project save(Project project);

    void update(Project project);

    Optional<Project> findById(ProjectId id);
}
