package com.dju.labflow.project.project;

import com.dju.labflow.project.project.domain.ProjectVisibility;

public record ProjectCreateCommand(String name, String description, ProjectVisibility visibility) {
}
