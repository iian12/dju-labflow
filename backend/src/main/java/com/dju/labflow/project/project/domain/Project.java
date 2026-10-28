package com.dju.labflow.project.project.domain;

import com.dju.labflow.user.domain.UserId;
import lombok.Getter;

import java.time.Instant;

@Getter
public class Project {

    private final ProjectId id;
    private String name;
    private String description;
    private final UserId createdBy;
    private ProjectVisibility visibility;
    private final Instant createdAt;
    private Instant updatedAt;

    public Project(ProjectId id, String name, String description, UserId createdBy,  ProjectVisibility visibility, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.createdBy = createdBy;
        this.visibility = visibility;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Project create(ProjectId id, String name, String description, UserId createdBy, ProjectVisibility visibility, Instant createdAt) {
        return new Project(id, name, description, createdBy, visibility, createdAt, null);
    }

    public static Project restore(ProjectId id, String name, String description, UserId createdBy, ProjectVisibility visibility, Instant createdAt, Instant updatedAt) {
        return new Project(id, name, description, createdBy, visibility, createdAt, updatedAt);
    }

}
