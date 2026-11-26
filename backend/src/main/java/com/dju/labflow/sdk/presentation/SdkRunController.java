package com.dju.labflow.sdk.presentation;

import com.dju.labflow.global.SecurityErrorResponse;
import com.dju.labflow.project.key.domain.ProjectApiKeyPrincipal;
import com.dju.labflow.run.run.application.*;
import com.dju.labflow.run.run.domain.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** SDK 인증과 도메인 작업을 연결한다. 멱등 응답 원장은 인증 뒤 공통 필터에서 처리한다. */
@RestController
@RequestMapping(value = "/api/v1/sdk/runs", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "SDK Runs", description = "프로젝트 API 키로 실험을 기록합니다. CSRF는 필요하지 않습니다.")
@SecurityRequirement(name = "projectApiKey")
@ApiResponses({
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request or key",
            content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class))),
    @ApiResponse(
            responseCode = "401",
            description = "Invalid API key",
            content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class))),
    @ApiResponse(
            responseCode = "403",
            description = "Forbidden",
            content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class))),
    @ApiResponse(
            responseCode = "404",
            description = "Not found",
            content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class))),
    @ApiResponse(
            responseCode = "409",
            description = "Processing or state conflict",
            content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class))),
    @ApiResponse(
            responseCode = "422",
            description = "Idempotency content mismatch",
            content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class)))
})
public class SdkRunController {
    private final RunService runs;
    private final RunRecordService records;
    private final RunHeartbeatPolicy heartbeats;

    public SdkRunController(
            RunService runs, RunRecordService records, RunHeartbeatPolicy heartbeats) {
        this.runs = runs;
        this.records = records;
        this.heartbeats = heartbeats;
    }

    @PostMapping
    @Operation(
            parameters =
                    @Parameter(
                            name = "Idempotency-Key",
                            in = ParameterIn.HEADER,
                            description = "선택 멱등키. 최대 300자, API 키·주소·메서드별 15일 유효. GET은 무시합니다.",
                            schema = @Schema(maxLength = 300)),
            summary = "실행 생성")
    @ApiResponse(responseCode = "201", description = "Created")
    public ResponseEntity<RunCreateResponse> create(
            @AuthenticationPrincipal ProjectApiKeyPrincipal principal,
            @Valid @RequestBody RunCreateRequest request) {
        RunId id = runs.create(principal, request.toCommand());
        URI location =
                ServletUriComponentsBuilder.fromCurrentRequestUri()
                        .path("/{runId}")
                        .buildAndExpand(id.value())
                        .toUri();
        return ResponseEntity.created(location).body(RunCreateResponse.from(id));
    }

    @GetMapping("/{runId}")
    @Operation(summary = "실행 조회")
    @ApiResponse(responseCode = "200", description = "Run")
    public ResponseEntity<RunResponse> get(
            @AuthenticationPrincipal ProjectApiKeyPrincipal principal,
            @Positive @PathVariable Long runId) {
        Run run = runs.get(principal, RunId.of(runId));
        return ResponseEntity.ok(RunResponse.from(run, heartbeats.status(run)));
    }

    @PutMapping("/{runId}/parameters")
    @Operation(
            parameters =
                    @Parameter(
                            name = "Idempotency-Key",
                            in = ParameterIn.HEADER,
                            description = "선택 멱등키. 최대 300자, API 키·주소·메서드별 15일 유효. GET은 무시합니다.",
                            schema = @Schema(maxLength = 300)),
            summary = "학습 파라미터 기록")
    @ApiResponse(responseCode = "204", description = "Recorded", content = @Content)
    public ResponseEntity<Void> parameters(
            @AuthenticationPrincipal ProjectApiKeyPrincipal principal,
            @Positive @PathVariable Long runId,
            @Valid @RequestBody RunParameterRequest request) {
        records.parameters(principal, RunId.of(runId), request.toCommand());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{runId}/metrics")
    @Operation(
            parameters =
                    @Parameter(
                            name = "Idempotency-Key",
                            in = ParameterIn.HEADER,
                            description = "선택 멱등키. 최대 300자, API 키·주소·메서드별 15일 유효. GET은 무시합니다.",
                            schema = @Schema(maxLength = 300)),
            summary = "숫자 메트릭 기록")
    @ApiResponse(responseCode = "204", description = "Recorded", content = @Content)
    public ResponseEntity<Void> metrics(
            @AuthenticationPrincipal ProjectApiKeyPrincipal principal,
            @Positive @PathVariable Long runId,
            @Valid @RequestBody MetricRequest request) {
        records.metrics(principal, RunId.of(runId), request.toCommand());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{runId}/logs")
    @Operation(
            parameters =
                    @Parameter(
                            name = "Idempotency-Key",
                            in = ParameterIn.HEADER,
                            description = "선택 멱등키. 최대 300자, API 키·주소·메서드별 15일 유효. GET은 무시합니다.",
                            schema = @Schema(maxLength = 300)),
            summary = "로그 기록")
    @ApiResponse(responseCode = "204", description = "Recorded", content = @Content)
    public ResponseEntity<Void> log(
            @AuthenticationPrincipal ProjectApiKeyPrincipal principal,
            @RequestHeader(value = "X-Labflow-Replay", defaultValue = "false") boolean replay,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Positive @PathVariable Long runId,
            @Valid @RequestBody LogCreateRequest request) {
        if (replay && key == null)
            throw new IllegalArgumentException("Recovered logs require Idempotency-Key");
        if (replay) records.recoverLog(principal, RunId.of(runId), request.toCommand());
        else records.log(principal, RunId.of(runId), request.toCommand());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{runId}/texts")
    @Operation(
            parameters =
                    @Parameter(
                            name = "Idempotency-Key",
                            in = ParameterIn.HEADER,
                            description = "선택 멱등키. 최대 300자, API 키·주소·메서드별 15일 유효. GET은 무시합니다.",
                            schema = @Schema(maxLength = 300)),
            summary = "텍스트 기록")
    @ApiResponse(responseCode = "204", description = "Recorded", content = @Content)
    public ResponseEntity<Void> text(
            @AuthenticationPrincipal ProjectApiKeyPrincipal principal,
            @Positive @PathVariable Long runId,
            @Valid @RequestBody TextCreateRequest request) {
        records.text(principal, RunId.of(runId), request.toCommand());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{runId}/system-metrics")
    @Operation(
            parameters =
                    @Parameter(
                            name = "Idempotency-Key",
                            in = ParameterIn.HEADER,
                            description = "선택 멱등키. 최대 300자, API 키·주소·메서드별 15일 유효. GET은 무시합니다.",
                            schema = @Schema(maxLength = 300)),
            summary = "시스템 지표 기록")
    @ApiResponse(responseCode = "204", description = "Recorded", content = @Content)
    public ResponseEntity<Void> systemMetric(
            @AuthenticationPrincipal ProjectApiKeyPrincipal principal,
            @Positive @PathVariable Long runId,
            @Valid @RequestBody SystemMetricCreateRequest request) {
        records.systemMetric(principal, RunId.of(runId), request.toCommand());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{runId}/heartbeat")
    @Operation(
            parameters =
                    @Parameter(
                            name = "Idempotency-Key",
                            in = ParameterIn.HEADER,
                            description = "선택 멱등키. 최대 300자, API 키·주소·메서드별 15일 유효. GET은 무시합니다.",
                            schema = @Schema(maxLength = 300)),
            summary = "생존 신호")
    @ApiResponse(responseCode = "204", description = "Recorded", content = @Content)
    public ResponseEntity<Void> heartbeat(
            @AuthenticationPrincipal ProjectApiKeyPrincipal principal,
            @Positive @PathVariable Long runId) {
        runs.heartbeat(principal, RunId.of(runId));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{runId}/complete")
    @Operation(
            parameters =
                    @Parameter(
                            name = "Idempotency-Key",
                            in = ParameterIn.HEADER,
                            description = "선택 멱등키. 최대 300자, API 키·주소·메서드별 15일 유효. GET은 무시합니다.",
                            schema = @Schema(maxLength = 300)),
            summary = "실행 완료")
    @ApiResponse(responseCode = "204", description = "Recorded", content = @Content)
    public ResponseEntity<Void> complete(
            @AuthenticationPrincipal ProjectApiKeyPrincipal principal,
            @Positive @PathVariable Long runId) {
        runs.complete(principal, RunId.of(runId));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{runId}/fail")
    @Operation(
            parameters =
                    @Parameter(
                            name = "Idempotency-Key",
                            in = ParameterIn.HEADER,
                            description = "선택 멱등키. 최대 300자, API 키·주소·메서드별 15일 유효. GET은 무시합니다.",
                            schema = @Schema(maxLength = 300)),
            summary = "실행 실패")
    @ApiResponse(responseCode = "204", description = "Recorded", content = @Content)
    public ResponseEntity<Void> fail(
            @AuthenticationPrincipal ProjectApiKeyPrincipal principal,
            @Positive @PathVariable Long runId,
            @Valid @RequestBody RunFailRequest request) {
        runs.fail(principal, RunId.of(runId), request.toCommand());
        return ResponseEntity.noContent().build();
    }
}
