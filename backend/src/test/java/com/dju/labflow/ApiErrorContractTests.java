package com.dju.labflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dju.labflow.auth.application.SignUpCommand;
import com.dju.labflow.global.handler.ApiExceptionHandler;
import com.dju.labflow.user.exception.DuplicateEmailException;
import jakarta.validation.Valid;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

/** 아직 HTTP로 노출하지 않은 서비스 예외도 공통 Advice에서 올바르게 매핑되는지 검증한다. */
class ApiErrorContractTests {
    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new ErrorProbe())
                    .setControllerAdvice(new ApiExceptionHandler())
                    .build();

    @Test
    void duplicateEmailReturns409() throws Exception {
        mvc.perform(post("/test-errors/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
    }

    @Test
    void databaseConflictDoesNotLeakSqlDetails() throws Exception {
        mvc.perform(post("/test-errors/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CONFLICT"))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "private SQL"))));
    }

    @Test
    void nullPasswordIs400WithFieldErrorsInsteadOf500() throws Exception {
        mvc.perform(
                        post("/test-errors/signup")
                                .contentType("application/json")
                                .content(
                                        "{\"email\":\"person@example.test\",\"password\":null,\"confirmPassword\":null,\"name\":\"test\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    /** 이 가상 컨트롤러는 일반 애플리케이션이나 Swagger 검색에 등록되지 않는다. */
    @RestController
    @Profile("error-contract-probe-only")
    @RequestMapping("/test-errors")
    static class ErrorProbe {
        @PostMapping("/duplicate")
        void duplicate() {
            throw new DuplicateEmailException();
        }

        @PostMapping("/conflict")
        void conflict() {
            throw new DataIntegrityViolationException("private SQL");
        }

        @PostMapping("/signup")
        void signup(@Valid @RequestBody SignUpCommand command) {}
    }
}
