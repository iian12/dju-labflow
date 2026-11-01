package com.dju.labflow.run.run.presentation;

import com.dju.labflow.global.resolver.CurrentUserId;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.run.run.application.*;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.user.domain.UserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.*;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.*;

/** PUBLIC은 공개 조회, PRIVATE은 소유자만 읽는 웹 전용 Run API. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs")
@Tag(name = "Project Runs", description = "웹 화면의 프로젝트별 실행·기록 조회")
public class RunQueryController {
    private final RunQueryService service;

    public RunQueryController(RunQueryService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "실행 목록과 상태 집계")
    public RunListResponse list(
            @Parameter(hidden = true) @CurrentUserId(required = false) @Nullable UserId user,
            @PathVariable @Positive Long projectId,
            @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return RunListResponse.from(
                service.list(user, ProjectId.of(projectId), new RunListQuery(page, size)));
    }

    @GetMapping("/{runId}")
    @Operation(summary = "실행 상세")
    public RunQueryResponse get(
            @Parameter(hidden = true) @CurrentUserId(required = false) @Nullable UserId user,
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long runId) {
        return RunQueryResponse.from(service.get(user, ProjectId.of(projectId), RunId.of(runId)));
    }

    @GetMapping("/{runId}/records")
    @Operation(summary = "실행 기록 조회", description = "각 시계열은 기록 시각/ID 순으로 최대 limit개를 반환합니다.")
    public RunRecordsResponse records(
            @Parameter(hidden = true) @CurrentUserId(required = false) @Nullable UserId user,
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long runId,
            @RequestParam(defaultValue = "1000") @Min(1) @Max(1000) int limit) {
        return RunRecordsResponse.from(
                service.records(user, ProjectId.of(projectId), RunId.of(runId), limit));
    }
}
