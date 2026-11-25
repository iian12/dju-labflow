package com.dju.labflow.project.key.presentation;

import com.dju.labflow.global.resolver.CurrentUserId;
import com.dju.labflow.project.key.application.ProjectApiKeyService;
import com.dju.labflow.project.key.domain.*;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.user.domain.UserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 키 원문/해시를 제외한 소유자 전용 목록 및 폐기 API. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/api-keys")
public class ProjectKeyManagementController {
    private final ProjectApiKeyService service;

    public ProjectKeyManagementController(ProjectApiKeyService service) {
        this.service = service;
    }

    public record Key(
            Long apiKeyId,
            String name,
            String keyPrefix,
            ProjectApiKeyStatus status,
            Instant createdAt,
            Instant revokedAt,
            Instant lastUsedAt) {}

    public record Keys(List<Key> items, int page, int size, long totalElements, long totalPages) {}

    @GetMapping
    @Operation(summary = "소유 프로젝트 API 키 목록")
    public ResponseEntity<Keys> list(
            @Parameter(hidden = true) @CurrentUserId UserId user,
            @PathVariable @Positive Long projectId,
            @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        ProjectApiKeyService.KeyPage result =
                service.list(user, ProjectId.of(projectId), page, size);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(
                        new Keys(
                                result.items().stream()
                                        .map(
                                                key ->
                                                        new Key(
                                                                key.getId().value(),
                                                                key.getName(),
                                                                key.getKeyPrefix(),
                                                                key.getStatus(),
                                                                key.getCreatedAt(),
                                                                key.getRevokedAt(),
                                                                key.getLastUsedAt()))
                                        .toList(),
                                page,
                                size,
                                result.totalElements(),
                                result.totalPages()));
    }

    @DeleteMapping("/{keyId}")
    @Operation(summary = "소유 프로젝트 API 키 폐기", description = "이미 폐기된 키에도 204를 반환합니다.")
    public ResponseEntity<Void> revoke(
            @Parameter(hidden = true) @CurrentUserId UserId user,
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long keyId) {
        service.revoke(user, ProjectId.of(projectId), ProjectApiKeyId.of(keyId));
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }
}
