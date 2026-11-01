package com.dju.labflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dju.labflow.auth.application.SignUpCommand;
import com.dju.labflow.global.handler.ApiExceptionHandler;
import com.dju.labflow.run.run.exception.RunNotFoundException;
import com.dju.labflow.run.run.exception.RunStateConflictException;
import com.dju.labflow.user.exception.DuplicateEmailException;
import jakarta.validation.Valid;
import java.sql.SQLDataException;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
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
    void databaseCapacityMismatchIsNotReportedAsADuplicateConflict() throws Exception {
        mvc.perform(post("/test-errors/storage-capacity"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("DATA_STORAGE_ERROR"))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "private SQL or text"))));
    }

    @Test
    void staleStateReturns409WithoutInternalDetails() throws Exception {
        mvc.perform(post("/test-errors/stale"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_STATE"))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "private version details"))));
    }

    @Test
    void missingRunReturns404() throws Exception {
        mvc.perform(post("/test-errors/missing-run"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
    }

    @Test
    void runStateConflictReturns409WithoutFailureDetails() throws Exception {
        mvc.perform(post("/test-errors/run-state"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUN_STATE_CONFLICT"))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "private failure details"))));
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

        @PostMapping("/storage-capacity")
        void storageCapacity() {
            throw new DataIntegrityViolationException(
                    "private SQL or text",
                    new SQLDataException("private SQL or text", "22001", 1406));
        }

        @PostMapping("/signup")
        void signup(@Valid @RequestBody SignUpCommand command) {}

        @PostMapping("/stale")
        void stale() {
            throw new OptimisticLockingFailureException("private version details");
        }

        @PostMapping("/missing-run")
        void missingRun() {
            throw new RunNotFoundException();
        }

        @PostMapping("/run-state")
        void runState() {
            throw new RunStateConflictException("private failure details");
        }
    }
}
