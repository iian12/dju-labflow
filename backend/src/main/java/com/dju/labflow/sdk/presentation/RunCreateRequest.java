package com.dju.labflow.sdk.presentation;

import com.dju.labflow.run.run.application.RunCreateCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.util.Map;

/** 실행 생성 요청. 프로젝트와 소유자는 요청 본문 대신 인증된 SDK 키에서 결정한다. */
public record RunCreateRequest(
        @NotBlank @Size(max = 100) @Schema(example = "resnet-training") String name,
        @Size(max = 100)
                Map<@NotBlank @Size(max = 100) String, @NotNull @Size(max = 2000) String>
                        metadata) {
    public RunCreateCommand toCommand() {
        return new RunCreateCommand(name, metadata);
    }
}
