package com.dju.labflow.project.project.presentation;

import com.dju.labflow.global.SecurityErrorResponse;
import com.dju.labflow.global.resolver.CurrentUserId;
import com.dju.labflow.project.project.application.ProjectListQuery;
import com.dju.labflow.project.project.application.ProjectService;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.user.domain.UserId;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.security.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.jspecify.annotations.Nullable;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** 개인 프로젝트 CRUD HTTP API. 변경 요청은 사용자 쿠키와 CSRF를 요구한다. */
@RestController
@RequestMapping(value = "/api/v1/projects", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Projects", description = "개인 소유 PUBLIC/PRIVATE 프로젝트 관리")
@ApiResponses({
    @ApiResponse(
            responseCode = "400",
            description = "잘못된 입력 또는 필수 version 누락",
            content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class))),
    @ApiResponse(
            responseCode = "401",
            description = "로그인이 필요하거나 비활성 계정",
            content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class))),
    @ApiResponse(
            responseCode = "403",
            description = "소유자 권한 없음 또는 CSRF 실패",
            content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class))),
    @ApiResponse(
            responseCode = "404",
            description = "프로젝트 없음",
            content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class))),
    @ApiResponse(
            responseCode = "409",
            description = "조회 버전 또는 저장 상태 충돌",
            content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class)))
})
public class ProjectController {
    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    @Operation(summary = "프로젝트 생성")
    @ApiResponse(responseCode = "201", description = "프로젝트 ID와 Location 반환")
    public ResponseEntity<ProjectCreateResponse> create(
            @Parameter(hidden = true) @CurrentUserId UserId userId,
            @Valid @RequestBody ProjectCreateRequest request) {
        ProjectId id = projectService.create(userId, request.toCommand());
        var location =
                ServletUriComponentsBuilder.fromCurrentRequestUri()
                        .path("/{projectId}")
                        .buildAndExpand(id.value())
                        .toUri();
        return ResponseEntity.created(location).body(ProjectCreateResponse.from(id));
    }

    @GetMapping
    @SecurityRequirement(name = "cookieAuth")
    @Operation(
            summary = "내 프로젝트 목록",
            description = "다른 사용자의 PUBLIC 프로젝트도 이 개인 목록에는 포함하지 않습니다. page는 0부터, size는 1~100입니다.")
    @ApiResponse(responseCode = "200", description = "개인 프로젝트 페이지")
    public ResponseEntity<ProjectListResponse> list(
            @Parameter(hidden = true) @CurrentUserId UserId userId,
            @RequestParam(value = "page", defaultValue = "0") @PositiveOrZero int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(
                ProjectListResponse.from(
                        projectService.list(userId, new ProjectListQuery(page, size))));
    }

    @GetMapping("/{projectId}")
    @SecurityRequirements
    @Operation(
            summary = "프로젝트 상세",
            description =
                    "PUBLIC은 로그인 없이 조회할 수 있습니다. PRIVATE는 소유자 쿠키가 필요하며 비로그인은 401, 다른 사용자는 403입니다.")
    @ApiResponse(responseCode = "200", description = "프로젝트 상세와 version")
    public ResponseEntity<ProjectResponse> get(
            @Parameter(hidden = true) @CurrentUserId(required = false) @Nullable UserId userId,
            @PathVariable("projectId") @Positive Long projectId) {
        return ResponseEntity.ok(
                ProjectResponse.from(projectService.get(userId, ProjectId.of(projectId))));
    }

    @PutMapping("/{projectId}")
    @Operation(
            summary = "프로젝트 수정",
            description = "소유자만 수정할 수 있습니다. 마지막으로 조회한 version이 일치해야 하며 성공 후 다시 조회합니다.")
    @ApiResponse(responseCode = "204", description = "수정 완료", content = @Content)
    public ResponseEntity<Void> update(
            @Parameter(hidden = true) @CurrentUserId UserId userId,
            @PathVariable("projectId") @Positive Long projectId,
            @Valid @RequestBody ProjectUpdateRequest request) {
        projectService.update(userId, ProjectId.of(projectId), request.toCommand());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{projectId}")
    @Operation(
            summary = "프로젝트 삭제",
            description = "소유자와 최신 version을 확인한 뒤 키·Run·모든 기록까지 한 트랜잭션에서 영구 삭제합니다.")
    @ApiResponse(responseCode = "204", description = "프로젝트와 종속 데이터 삭제 완료", content = @Content)
    public ResponseEntity<Void> delete(
            @Parameter(hidden = true) @CurrentUserId UserId userId,
            @PathVariable("projectId") @Positive Long projectId,
            @RequestParam("version") @PositiveOrZero Long version) {
        projectService.delete(userId, ProjectId.of(projectId), version);
        return ResponseEntity.noContent().build();
    }
}
