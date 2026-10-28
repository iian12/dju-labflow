package com.dju.labflow.project.project.infrastructure.persistence;

import com.dju.labflow.global.id.AssignedIdEntity;
import com.dju.labflow.project.project.domain.ProjectVisibility;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectEntity extends AssignedIdEntity {

    private String name;
    private String description;
    private Long createdBy;

    @Enumerated(EnumType.STRING)
    private ProjectVisibility visibility;

    private Instant createdAt;
    private Instant updatedAt;

    @Builder
    public ProjectEntity(Long id, String name, String description, Long createdBy, ProjectVisibility visibility, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.createdBy = createdBy;
        this.visibility = visibility;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
