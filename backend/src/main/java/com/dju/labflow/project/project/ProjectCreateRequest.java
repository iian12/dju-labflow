package com.dju.labflow.project.project;

import com.dju.labflow.project.project.domain.ProjectVisibility;

public record ProjectCreateRequest(String name, String description, ProjectVisibility visibility) {
    public ProjectCreateCommand toCommand() {
        return new ProjectCreateCommand(
                name,
                description,
                visibility
        );
    }
}
