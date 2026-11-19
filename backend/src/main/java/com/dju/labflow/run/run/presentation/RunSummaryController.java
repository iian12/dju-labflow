package com.dju.labflow.run.run.presentation;

import com.dju.labflow.global.resolver.CurrentUserId;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.run.run.application.RunSummaryService;
import com.dju.labflow.user.domain.UserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.*;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.*;

/** 웹 Run 목록의 지표 요약 조회. 프로젝트 공개 정책을 그대로 적용한다. */
@RestController
public class RunSummaryController {
    private final RunSummaryService service;

    public RunSummaryController(RunSummaryService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/projects/{projectId}/runs/summaries")
    @Operation(
            summary = "Run 목록 지표 요약",
            description = "같은 프로젝트의 Run 1~100개의 최신 지표와 마지막 Step을 선택 순서로 반환합니다.")
    public List<RunSummaryResponse> summaries(
            @Parameter(hidden = true) @CurrentUserId(required = false) @Nullable UserId user,
            @PathVariable @Positive Long projectId,
            @RequestParam @Size(min = 1, max = 100) List<@Positive Long> runIds) {
        return service.summaries(user, ProjectId.of(projectId), runIds).stream()
                .map(RunSummaryResponse::from)
                .toList();
    }

    public record Curve(
            String metricKey,
            RunComparisonResponse.MetricPoint latestMetric,
            RunComparisonResponse.Series series) {}

    @GetMapping("/api/v1/projects/{projectId}/runs/{runId}/metric-series")
    @Operation(
            summary = "단일 Run 지표 곡선",
            description = "전체 Step 범위에서 첫 점과 마지막 점을 보존하며 실제 기록을 샘플링합니다.")
    public Curve curve(
            @Parameter(hidden = true) @CurrentUserId(required = false) @Nullable UserId user,
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long runId,
            @RequestParam @NotBlank @Size(max = 100) String metricKey,
            @RequestParam(defaultValue = "500") @Min(2) @Max(1000) int maxPoints) {
        var result =
                service.curve(
                        user,
                        ProjectId.of(projectId),
                        com.dju.labflow.run.run.domain.RunId.of(runId),
                        metricKey,
                        maxPoints);
        return new Curve(
                result.metricKey(),
                RunComparisonResponse.MetricPoint.from(result.latestMetric()),
                new RunComparisonResponse.Series(
                        result.series().points().stream()
                                .map(RunComparisonResponse.MetricPoint::from)
                                .toList(),
                        result.series().totalPoints(),
                        result.series().sampled()));
    }
}
