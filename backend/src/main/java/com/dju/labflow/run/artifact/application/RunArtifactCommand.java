package com.dju.labflow.run.artifact.application;

import com.dju.labflow.run.artifact.domain.ArtifactKind;
import jakarta.validation.constraints.*;
import java.time.Instant;

/** 업로드 예약 입력. bucket과 objectKey는 애플리케이션에서 결정한다. 용량 한도는 정책에서 검사한다. */
public record RunArtifactCommand(
        @NotBlank @Size(max = 255) String name,
        @NotNull ArtifactKind kind,
        @NotBlank @Size(max = 255) String contentType,
        @NotNull @PositiveOrZero Long expectedSizeBytes,
        @NotBlank @Size(max = 255) String bucket,
        @NotBlank @Size(max = 512) String objectKey,
        @PositiveOrZero Long step,
        @NotNull Instant uploadExpiresAt) {}
