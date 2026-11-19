package com.dju.labflow.run.run.presentation;

import com.dju.labflow.global.resolver.CurrentUserId;
import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.run.artifact.application.LocalArtifactService;
import com.dju.labflow.run.artifact.domain.ArtifactStatus;
import com.dju.labflow.run.artifact.infrastructure.persistence.RunArtifactJpaRepository;
import com.dju.labflow.run.run.application.RunQueryService;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.user.domain.UserId;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs/{runId}/artifacts")
public class RunArtifactQueryController {
    private final RunQueryService runs;
    private final RunArtifactJpaRepository artifacts;
    private final LocalArtifactService files;

    public RunArtifactQueryController(
            RunQueryService runs, RunArtifactJpaRepository artifacts, LocalArtifactService files) {
        this.runs = runs;
        this.artifacts = artifacts;
        this.files = files;
    }

    public record Item(long artifactId, String name, long sizeBytes) {}

    public record Listing(
            List<Item> items, int page, int size, int totalPages, long totalElements) {}

    @GetMapping
    public Listing list(
            @CurrentUserId(required = false) @Nullable UserId user,
            @PathVariable long projectId,
            @PathVariable long runId,
            @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int size) {
        runs.get(user, ProjectId.of(projectId), RunId.of(runId));
        var result =
                artifacts.findByRunIdAndStatusAndBucketOrderByIdAsc(
                        runId, ArtifactStatus.READY, "local", PageRequest.of(page, size));
        return new Listing(
                result.map(a -> new Item(a.getId(), a.getName(), a.getSizeBytes())).getContent(),
                page,
                size,
                result.getTotalPages(),
                result.getTotalElements());
    }

    @GetMapping("/{artifactId}")
    public ResponseEntity<FileSystemResource> download(
            @CurrentUserId(required = false) @Nullable UserId user,
            @PathVariable long projectId,
            @PathVariable long runId,
            @PathVariable long artifactId) {
        runs.get(user, ProjectId.of(projectId), RunId.of(runId));
        var file = files.localDownload(RunId.of(runId), artifactId);
        String name = file.metadata().getName().replaceAll("[\\p{Cntrl}/\\\\]", "_");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.metadata().getSizeBytes())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(name, StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(new FileSystemResource(file.path()));
    }
}
