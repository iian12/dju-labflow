package com.dju.labflow.run.run.presentation;

import com.dju.labflow.global.resolver.CurrentUserId;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.run.run.application.*;
import com.dju.labflow.run.run.domain.RecordKind;
import com.dju.labflow.run.run.domain.Run;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.run.run.domain.RunSearch;
import com.dju.labflow.run.run.domain.RunStatus;
import com.dju.labflow.user.domain.UserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.*;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.*;

/** PUBLIC은 공개 조회, PRIVATE은 소유자만 읽는 웹 전용 Run API. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs")
@Tag(name = "Project Runs", description = "웹 화면의 프로젝트별 실행·기록 조회")
public class RunQueryController {
    private final RunQueryService service;
    private final RunHeartbeatPolicy heartbeats;

    public RunQueryController(RunQueryService service, RunHeartbeatPolicy heartbeats) {
        this.service = service;
        this.heartbeats = heartbeats;
    }

    @GetMapping
    @Operation(summary = "실행 목록과 상태 집계")
    public RunListResponse list(
            @Parameter(hidden = true) @CurrentUserId(required = false) @Nullable UserId user,
            @PathVariable @Positive Long projectId,
            @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "") @Size(max = 100) String search,
            @RequestParam(required = false) List<RunStatus> status,
            @RequestParam(defaultValue = "recent") String sort,
            @RequestParam(defaultValue = "") @Size(max = 100) String metricKey,
            @RequestParam(required = false) List<Long> runIds) {
        return RunListResponse.from(
                service.list(
                        user,
                        ProjectId.of(projectId),
                        new RunListQuery(
                                page,
                                size,
                                new RunSearch(search, status, sort, metricKey, runIds))),
                heartbeats);
    }

    @GetMapping("/{runId}")
    @Operation(summary = "실행 상세")
    public RunQueryResponse get(
            @Parameter(hidden = true) @CurrentUserId(required = false) @Nullable UserId user,
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long runId) {
        Run run = service.get(user, ProjectId.of(projectId), RunId.of(runId));
        return RunQueryResponse.from(run, heartbeats.status(run));
    }

    @GetMapping("/{runId}/records")
    @Operation(
            summary = "실행 기록 조회",
            description = "기본은 처음 limit개, latest=true이면 최근 limit개를 시간/ID 오름차순으로 반환합니다.")
    public RunRecordsResponse records(
            @Parameter(hidden = true) @CurrentUserId(required = false) @Nullable UserId user,
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long runId,
            @RequestParam(defaultValue = "1000") @Min(1) @Max(1000) int limit,
            @RequestParam(defaultValue = "false") boolean latest) {
        return RunRecordsResponse.from(
                service.records(user, ProjectId.of(projectId), RunId.of(runId), limit, latest));
    }

    @GetMapping("/{runId}/records/{kind}")
    @Operation(
            summary = "기록 커서 페이지 조회",
            description =
                    "kind는 metrics/logs/texts/system-metrics입니다. "
                            + "처음 조회는 오름차순, latest=true는 최근 기록, before/after는 해당 커서 이전/이후입니다. "
                            + "세 옵션은 함께 사용할 수 없습니다. items는 항상 시간/ID 오름차순이며 hasMore/nextCursor는 조회 방향 기준입니다.")
    public RunRecordPageResponse recordPage(
            @Parameter(hidden = true) @CurrentUserId(required = false) @Nullable UserId user,
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long runId,
            @PathVariable String kind,
            @RequestParam(defaultValue = "200") @Min(1) @Max(1000) int limit,
            @RequestParam(required = false) String before,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "false") boolean latest) {
        return RunRecordPageResponse.from(
                service.recordPage(
                        user,
                        ProjectId.of(projectId),
                        RunId.of(runId),
                        RecordKind.fromPath(kind),
                        limit,
                        before,
                        after,
                        latest));
    }
}
