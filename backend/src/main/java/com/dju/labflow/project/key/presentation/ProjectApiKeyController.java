package com.dju.labflow.project.key.presentation;

import com.dju.labflow.global.resolver.CurrentUserId;
import com.dju.labflow.project.key.application.IssueProjectApiKeyResult;
import com.dju.labflow.project.key.application.ProjectApiKeyService;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.user.domain.UserId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/project-api-keys")
public class ProjectApiKeyController {

    private final ProjectApiKeyService projectApiKeyService;

    public ProjectApiKeyController(ProjectApiKeyService projectApiKeyService) {
        this.projectApiKeyService = projectApiKeyService;
    }

    @PostMapping("/issue/{projectId}")
    public ResponseEntity<IssueProjectApiKeyResponse> issueProjectApiKey(@CurrentUserId UserId userId,
                                                                         @PathVariable("projectId") Long projectId,
                                                                         @RequestBody IssueProjectApiKeyRequest request) {
        IssueProjectApiKeyResult result = projectApiKeyService.issue(userId, ProjectId.of(projectId), request);
        IssueProjectApiKeyResponse response = IssueProjectApiKeyResponse.from(result);
        return ResponseEntity.ok(response);
    }
}
