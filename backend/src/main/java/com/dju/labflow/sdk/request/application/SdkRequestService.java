package com.dju.labflow.sdk.request.application;

import com.dju.labflow.global.id.IdGenerator;
import com.dju.labflow.project.key.domain.ProjectApiKeyPrincipal;
import com.dju.labflow.project.project.domain.Project;
import com.dju.labflow.project.project.domain.repository.ProjectRepository;
import com.dju.labflow.project.project.exception.ProjectNotFoundException;
import com.dju.labflow.run.run.application.RunAccessPolicy;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.sdk.request.domain.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** 부모 잠금, 요청 비교, 실제 쓰기 및 성공 기록을 하나의 트랜잭션으로 처리한다. */
@Service
public class SdkRequestService {
    private final SdkRequestRepository requests;
    private final ProjectRepository projects;
    private final RunAccessPolicy access;
    private final IdGenerator ids;
    private final Clock clock;
    private final JsonMapper json;

    public SdkRequestService(
            SdkRequestRepository requests,
            ProjectRepository projects,
            RunAccessPolicy access,
            IdGenerator ids,
            Clock clock,
            JsonMapper json) {
        this.requests = requests;
        this.projects = projects;
        this.access = access;
        this.ids = ids;
        this.clock = clock;
        this.json = json;
    }

    @Transactional
    public RunId execute(
            ProjectApiKeyPrincipal principal,
            String requestId,
            String operation,
            RunId runId,
            Object payload,
            Supplier<RunId> write) {
        // 이전 클라이언트의 헤더 없는 요청은 기존 계약을 유지한다.
        if (requestId == null) return write.get();
        if (!requestId.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Invalid requestId");
        Project project =
                projects.findByIdForUpdate(principal.projectId())
                        .orElseThrow(ProjectNotFoundException::new);
        if (!project.getCreatedBy().equals(principal.createdBy()))
            throw new AccessDeniedException("Project belongs to another owner");
        // 캐시를 반환하기 전에도 현재 프로젝트/Run 접근 권한을 검사한다.
        if (runId != null) access.requireOwnedForUpdate(principal, runId);
        String requestKey = hash(requestId);
        String fingerprint = fingerprint(operation, runId, payload);
        Optional<SdkRequest> previous = requests.find(principal.projectId(), requestKey);
        if (previous.isPresent()) {
            if (!previous.get().fingerprint().equals(fingerprint))
                throw new RequestIdConflictException();
            return previous.get().resultRunId();
        }
        RunId result = write.get();
        requests.save(
                new SdkRequest(
                        ids.nextId(),
                        principal.projectId(),
                        requestKey,
                        fingerprint,
                        result,
                        clock.instant()));
        return result;
    }

    private String fingerprint(String operation, RunId runId, Object payload) {
        Object value = json.convertValue(payload, Object.class);
        String canonical =
                json.writeValueAsString(
                        Arrays.asList(
                                operation, runId == null ? null : runId.value(), ordered(value)));
        return hash(canonical);
    }

    private String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private Object ordered(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put((String) key, ordered(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(this::ordered).toList();
        return value;
    }
}
