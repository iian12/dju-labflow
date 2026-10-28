package com.dju.labflow.project.project.domain;

import com.dju.labflow.global.validation.InputValidation;
import com.dju.labflow.user.domain.UserId;
import java.time.Instant;
import lombok.Getter;

/** 프로젝트의 소유자와 식별자를 유지하면서 편집 가능한 정보 및 수정 시각을 관리한다. */
@Getter
public class Project {

    private final ProjectId id;
    private String name;
    private String description;
    private final UserId createdBy;
    private ProjectVisibility visibility;
    private final Instant createdAt;
    private Instant updatedAt;

    public Project(
            ProjectId id,
            String name,
            String description,
            UserId createdBy,
            ProjectVisibility visibility,
            Instant createdAt,
            Instant updatedAt) {
        this.id = InputValidation.required(id, "Project ID");
        this.name = InputValidation.text(name, "Project name", 100);
        this.description = InputValidation.optionalText(description, "Description", 2000);
        this.createdBy = InputValidation.required(createdBy, "Project owner");
        this.visibility = InputValidation.required(visibility, "Visibility");
        this.createdAt = InputValidation.required(createdAt, "Creation time");
        this.updatedAt = updatedAt;
    }

    public static Project create(
            ProjectId id,
            String name,
            String description,
            UserId createdBy,
            ProjectVisibility visibility,
            Instant createdAt) {
        return new Project(id, name, description, createdBy, visibility, createdAt, null);
    }

    public static Project restore(
            ProjectId id,
            String name,
            String description,
            UserId createdBy,
            ProjectVisibility visibility,
            Instant createdAt,
            Instant updatedAt) {
        return new Project(id, name, description, createdBy, visibility, createdAt, updatedAt);
    }

    /** 생성자/소유자/생성 시각은 유지하고 편집 가능한 정보만 변경한다. */
    public void updateDetails(
            String name, String description, ProjectVisibility visibility, Instant now) {
        InputValidation.text(name, "Project name", 100);
        InputValidation.optionalText(description, "Description", 2000);
        InputValidation.required(visibility, "Visibility");
        InputValidation.required(now, "Update time");
        if (now.isBefore(createdAt) || (updatedAt != null && now.isBefore(updatedAt))) {
            throw new IllegalArgumentException(
                    "Update time must not precede the current project state");
        }
        this.name = name;
        this.description = description;
        this.visibility = visibility;
        this.updatedAt = now;
    }
}
