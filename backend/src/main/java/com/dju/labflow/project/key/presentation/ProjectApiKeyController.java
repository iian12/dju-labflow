package com.dju.labflow.project.key.presentation;

import com.dju.labflow.global.resolver.CurrentUserId;
import com.dju.labflow.project.key.application.IssueProjectApiKeyResult;
import com.dju.labflow.project.key.application.ProjectApiKeyService;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.user.domain.UserId;
import com.dju.labflow.global.SecurityErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 프로젝트 소유자의 키 발급 HTTP API와 검증·OpenAPI 계약을 정의한다. */
@RestController
@RequestMapping("/api/v1/project-api-keys")
@Tag(name = "Project API Keys", description = "프로젝트 소유자의 API 키 발급")
public class ProjectApiKeyController {

    private final ProjectApiKeyService projectApiKeyService;

    public ProjectApiKeyController(ProjectApiKeyService projectApiKeyService) {
        this.projectApiKeyService = projectApiKeyService;
    }

    @PostMapping("/issue/{projectId}")
    @Operation(summary = "프로젝트 API 키 발급", description = "프로젝트 소유자만 발급할 수 있습니다. 원문은 이 응답에서만 제공됩니다.")
    @ApiResponse(responseCode = "200", description = "발급 완료")
    @ApiResponse(responseCode = "400", description = "잘못된 입력", content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "인증되지 않았거나 비활성 계정", content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "소유자가 아니거나 CSRF 검증 실패", content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "프로젝트 없음", content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "저장 데이터 충돌", content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class)))
    public ResponseEntity<IssueProjectApiKeyResponse> issueProjectApiKey(@Parameter(hidden = true) @CurrentUserId UserId userId,
                                                                         @Positive @PathVariable("projectId") Long projectId,
                                                                         @Valid @RequestBody IssueProjectApiKeyRequest request) {
        IssueProjectApiKeyResult result = projectApiKeyService.issue(userId, ProjectId.of(projectId), request.toCommand());
        IssueProjectApiKeyResponse response = IssueProjectApiKeyResponse.from(result);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(response);
    }
}
