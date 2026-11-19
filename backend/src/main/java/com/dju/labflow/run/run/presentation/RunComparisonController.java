package com.dju.labflow.run.run.presentation;

import com.dju.labflow.global.resolver.CurrentUserId;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.run.run.application.RunComparisonQuery;
import com.dju.labflow.run.run.application.RunComparisonService;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.user.domain.UserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.*;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.*;

/** 프로젝트 공개 정책을 적용하는 웹 전용 Run 비교 API. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs/compare")
@Tag(name = "Project Runs")
public class RunComparisonController {
    private final RunComparisonService service;

    public RunComparisonController(RunComparisonService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(
            summary = "Run 2~4개 비교",
            description =
                    "선택 순서대로 지표 곡선·최신값·마지막 공통 Step의 값·기준 대비 차이·파라미터 차이를 반환합니다. "
                            + "metricKey는 실제 기록한 지표 이름입니다. 기준 Run 생략 시 첫 Run을 사용합니다. "
                            + "공통 Step이 없으면 공통값/차이는 null입니다. 곡선은 Step 중복 제거 후 전체 범위에서 "
                            + "maxPoints개 이하로 샘플링하며 요약값은 원본 전체로 계산합니다.")
    public RunComparisonResponse compare(
            @Parameter(hidden = true) @CurrentUserId(required = false) @Nullable UserId user,
            @PathVariable @Positive Long projectId,
            @RequestParam @Size(min = 2, max = 4) List<@Positive Long> runIds,
            @RequestParam @NotBlank @Size(max = 100) String metricKey,
            @RequestParam(required = false) @Positive Long baselineRunId,
            @RequestParam(defaultValue = "500") @Min(2) @Max(1000) int maxPoints,
            @RequestParam(defaultValue = "false") boolean differencesOnly) {
        return RunComparisonResponse.from(
                service.compare(
                        user,
                        ProjectId.of(projectId),
                        new RunComparisonQuery(
                                runIds.stream().map(RunId::of).toList(),
                                metricKey,
                                baselineRunId == null ? null : RunId.of(baselineRunId),
                                maxPoints,
                                differencesOnly)));
    }
}
