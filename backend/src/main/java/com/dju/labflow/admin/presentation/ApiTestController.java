package com.dju.labflow.admin.presentation;

import io.swagger.v3.oas.annotations.Operation;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** ADMIN 전용 테스트 도구의 접근 확인과 현재 서버의 API 목록. 실제 API의 권한은 유지한다. */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class ApiTestController {
    private final RequestMappingHandlerMapping mappings;

    public ApiTestController(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings) {
        this.mappings = mappings;
    }

    public record ApiOperation(String method, String path) {}

    @GetMapping("/api/v1/admin/api-test")
    @Operation(summary = "관리자 API 테스트 접근 확인 및 API 목록")
    public ResponseEntity<List<ApiOperation>> catalog() {
        var operations =
                mappings.getHandlerMethods().entrySet().stream()
                        .filter(
                                entry ->
                                        entry.getValue()
                                                .getBeanType()
                                                .getPackageName()
                                                .startsWith("com.dju.labflow"))
                        .flatMap(
                                entry ->
                                        entry.getKey().getPatternValues().stream()
                                                .filter(path -> path.startsWith("/api/v1/"))
                                                .flatMap(
                                                        path ->
                                                                entry
                                                                        .getKey()
                                                                        .getMethodsCondition()
                                                                        .getMethods()
                                                                        .stream()
                                                                        .map(
                                                                                method ->
                                                                                        new ApiOperation(
                                                                                                method
                                                                                                        .name(),
                                                                                                path))))
                        .sorted(
                                Comparator.comparing(ApiOperation::path)
                                        .thenComparing(ApiOperation::method))
                        .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(operations);
    }
}
