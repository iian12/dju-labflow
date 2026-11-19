package com.dju.labflow.run.run.presentation;

import com.dju.labflow.global.resolver.CurrentUserId;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.run.run.application.RunManagementService;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.user.domain.UserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs")
@Tag(name = "Project Runs")
public class RunManagementController {
    private final RunManagementService service;

    public RunManagementController(RunManagementService service) {
        this.service = service;
    }

    @PostMapping("/{runId}/stop")
    @Operation(
            summary = "소유자가 실행을 수동 종료",
            description =
                    "쿠키 로그인과 CSRF가 필요합니다. Run을 FAILED로 기록하며 클라이언트의 학습 프로세스를 중지하지 않습니다. 같은 종료 재요청은 멱등적입니다.")
    public ResponseEntity<Void> stop(
            @Parameter(hidden = true) @CurrentUserId UserId user,
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long runId) {
        service.stop(user, ProjectId.of(projectId), RunId.of(runId));
        return ResponseEntity.noContent().build();
    }
}
