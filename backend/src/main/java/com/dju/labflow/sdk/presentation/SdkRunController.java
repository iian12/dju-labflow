package com.dju.labflow.sdk.presentation;

import com.dju.labflow.global.SecurityErrorResponse;
import com.dju.labflow.project.key.domain.ProjectApiKeyPrincipal;
import com.dju.labflow.run.run.application.RunRecordService;
import com.dju.labflow.run.run.application.RunService;
import com.dju.labflow.run.run.domain.RunId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** 프로젝트 Bearer API 키로 실행을 생성하고 기록/종료하는 SDK 전용 HTTP API. */
@RestController
@RequestMapping(value = "/api/v1/sdk/runs", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "SDK Runs", description = "프로젝트 API 키로 실험 실행과 기록을 관리합니다. CSRF는 필요하지 않습니다.")
@SecurityRequirement(name = "projectApiKey")
@ApiResponses({
    @ApiResponse(
            responseCode = "400",
            description = "잘못된 요청 본문 또는 경로 인자",
            content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class))),
    @ApiResponse(
            responseCode = "401",
            description = "API 키 누락/오류/폐기 또는 비활성 소유자 계정",
            content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class))),
    @ApiResponse(
            responseCode = "403",
            description = "다른 프로젝트 또는 소유자의 실행에 접근",
            content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class))),
    @ApiResponse(
            responseCode = "404",
            description = "실행 또는 프로젝트 없음",
            content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class))),
    @ApiResponse(
            responseCode = "409",
            description = "종료 상태/파라미터 값/저장 버전 충돌",
            content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class)))
})
public class SdkRunController {
    private final RunService runService;
    private final RunRecordService runRecordService;

    public SdkRunController(RunService runService, RunRecordService runRecordService) {
        this.runService = runService;
        this.runRecordService = runRecordService;
    }

    @PostMapping
    @Operation(summary = "실행 생성", description = "프로젝트와 소유자는 인증된 키에서 결정합니다.")
    @ApiResponse(responseCode = "201", description = "RUNNING 실행 생성, Location 헤더로 조회 경로 제공")
    public ResponseEntity<RunCreateResponse> create(
            @Parameter(hidden = true) @AuthenticationPrincipal ProjectApiKeyPrincipal principal,
            @Valid @RequestBody RunCreateRequest request) {
        RunId runId = runService.create(principal, request.toCommand());
        var location =
                ServletUriComponentsBuilder.fromCurrentRequestUri()
                        .path("/{runId}")
                        .buildAndExpand(runId.value())
                        .toUri();
        return ResponseEntity.created(location).body(RunCreateResponse.from(runId));
    }

    @GetMapping("/{runId}")
    @Operation(summary = "실행 조회", description = "키에 연결된 프로젝트의 실행만 조회할 수 있습니다.")
    @ApiResponse(responseCode = "200", description = "실행 상태와 메타데이터")
    public ResponseEntity<RunResponse> get(
            @Parameter(hidden = true) @AuthenticationPrincipal ProjectApiKeyPrincipal principal,
            @Positive @PathVariable("runId") Long runId) {
        return ResponseEntity.ok(RunResponse.from(runService.get(principal, RunId.of(runId))));
    }

    @PutMapping("/{runId}/parameters")
    @Operation(summary = "실행 파라미터 추가", description = "실행 중 같은 키/값 재전송은 허용하고 값 변경은 거부합니다.")
    @ApiResponse(responseCode = "204", description = "파라미터 반영", content = @Content)
    public ResponseEntity<Void> parameters(
            @Parameter(hidden = true) @AuthenticationPrincipal ProjectApiKeyPrincipal principal,
            @Positive @PathVariable("runId") Long runId,
            @Valid @RequestBody RunParameterRequest request) {
        runRecordService.parameters(principal, RunId.of(runId), request.toCommand());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{runId}/metrics")
    @Operation(
            summary = "숫자 메트릭 기록",
            description = "숫자 형태의 문자열을 포함해 JSON 숫자가 아닌 값은 거부합니다. 배치는 함께 저장/롤백합니다.")
    @ApiResponse(responseCode = "204", description = "메트릭 기록", content = @Content)
    public ResponseEntity<Void> metrics(
            @Parameter(hidden = true) @AuthenticationPrincipal ProjectApiKeyPrincipal principal,
            @Positive @PathVariable("runId") Long runId,
            @Valid @RequestBody MetricRequest request) {
        runRecordService.metrics(principal, RunId.of(runId), request.toCommand());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{runId}/logs")
    @Operation(
            summary = "실행 로그 기록",
            description = "USER/STDOUT/STDERR/SDK 출처와 레벨을 명시합니다. 빈 출력도 보존합니다.")
    @ApiResponse(responseCode = "204", description = "로그 기록", content = @Content)
    public ResponseEntity<Void> log(
            @Parameter(hidden = true) @AuthenticationPrincipal ProjectApiKeyPrincipal principal,
            @Positive @PathVariable("runId") Long runId,
            @Valid @RequestBody LogCreateRequest request) {
        runRecordService.log(principal, RunId.of(runId), request.toCommand());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{runId}/texts")
    @Operation(summary = "실험 텍스트 기록", description = "모델 입력·출력·정답을 이름과 선택 step으로 기록합니다.")
    @ApiResponse(responseCode = "204", description = "텍스트 기록", content = @Content)
    public ResponseEntity<Void> text(
            @Parameter(hidden = true) @AuthenticationPrincipal ProjectApiKeyPrincipal principal,
            @Positive @PathVariable("runId") Long runId,
            @Valid @RequestBody TextCreateRequest request) {
        runRecordService.text(principal, RunId.of(runId), request.toCommand());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{runId}/system-metrics")
    @Operation(summary = "시스템 지표 기록", description = "CPU/GPU 사용률은 0~100, 메모리는 바이트, 경과 시간은 초 단위입니다.")
    @ApiResponse(responseCode = "204", description = "시스템 지표 기록", content = @Content)
    public ResponseEntity<Void> systemMetric(
            @Parameter(hidden = true) @AuthenticationPrincipal ProjectApiKeyPrincipal principal,
            @Positive @PathVariable("runId") Long runId,
            @Valid @RequestBody SystemMetricCreateRequest request) {
        runRecordService.systemMetric(principal, RunId.of(runId), request.toCommand());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{runId}/complete")
    @Operation(summary = "실행 완료", description = "본문 없이 호출합니다. 이미 완료된 실행의 재호출은 기존 종료 결과를 유지합니다.")
    @ApiResponse(responseCode = "204", description = "완료 처리", content = @Content)
    public ResponseEntity<Void> complete(
            @Parameter(hidden = true) @AuthenticationPrincipal ProjectApiKeyPrincipal principal,
            @Positive @PathVariable("runId") Long runId) {
        runService.complete(principal, RunId.of(runId));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{runId}/fail")
    @Operation(summary = "실행 실패", description = "같은 사유의 재전송은 허용하고 다른 종료 결과로의 변경은 거부합니다.")
    @ApiResponse(responseCode = "204", description = "실패 처리", content = @Content)
    public ResponseEntity<Void> fail(
            @Parameter(hidden = true) @AuthenticationPrincipal ProjectApiKeyPrincipal principal,
            @Positive @PathVariable("runId") Long runId,
            @Valid @RequestBody RunFailRequest request) {
        runService.fail(principal, RunId.of(runId), request.toCommand());
        return ResponseEntity.noContent().build();
    }
}
