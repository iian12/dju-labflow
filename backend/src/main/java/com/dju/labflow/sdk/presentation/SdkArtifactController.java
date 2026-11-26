package com.dju.labflow.sdk.presentation;

import com.dju.labflow.project.key.domain.ProjectApiKeyPrincipal;
import com.dju.labflow.run.artifact.application.LocalArtifactService;
import com.dju.labflow.run.artifact.domain.ArtifactKind;
import com.dju.labflow.run.artifact.domain.RunArtifact;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.sdk.idempotency.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sdk/runs/{runId}/artifacts")
public class SdkArtifactController {
    private final LocalArtifactService files;

    public SdkArtifactController(LocalArtifactService files) {
        this.files = files;
    }

    @PostMapping(consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Map<String, Long>> upload(
            @AuthenticationPrincipal ProjectApiKeyPrincipal principal,
            @PathVariable long runId,
            @RequestParam String name,
            @RequestParam(defaultValue = "OTHER") ArtifactKind kind,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest request)
            throws IOException {
        RunArtifact artifact =
                files.upload(
                        principal,
                        RunId.of(runId),
                        name,
                        kind,
                        (IdempotencyStore.Claim) request.getAttribute(IdempotencyFilter.CLAIM),
                        request.getContentLengthLong(),
                        request.getInputStream());
        return ResponseEntity.status(201).body(Map.of("artifactId", artifact.getId().value()));
    }

    @GetMapping("/{artifactId}")
    public ResponseEntity<FileSystemResource> download(
            @AuthenticationPrincipal ProjectApiKeyPrincipal principal,
            @PathVariable long runId,
            @PathVariable long artifactId) {
        LocalArtifactService.Download file = files.download(principal, RunId.of(runId), artifactId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.metadata().getSizeBytes())
                .header("X-Artifact-SHA256", file.metadata().getSha256())
                .body(new FileSystemResource(file.path()));
    }
}
