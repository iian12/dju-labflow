package com.dju.labflow.sdk.presentation;

import com.dju.labflow.run.parameter.application.RunParameterCommand;
import jakarta.validation.constraints.*;
import java.util.Map;

/** 실행 설정의 추가 요청. 같은 키/값은 재전송할 수 있으나 값은 변경할 수 없다. */
public record RunParameterRequest(
        @NotEmpty @Size(max = 100)
                Map<@NotBlank @Size(max = 100) String, @NotNull @Size(max = 2000) String>
                        parameters) {
    public RunParameterCommand toCommand() {
        return new RunParameterCommand(parameters);
    }
}
