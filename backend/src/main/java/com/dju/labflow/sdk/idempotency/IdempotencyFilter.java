package com.dju.labflow.sdk.idempotency;

import com.dju.labflow.global.SecurityErrorResponse;
import com.dju.labflow.project.key.domain.ProjectApiKeyPrincipal;
import com.dju.labflow.project.project.domain.Project;
import com.dju.labflow.project.project.domain.repository.ProjectRepository;
import com.dju.labflow.project.project.exception.ProjectNotFoundException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.json.JsonMapper;

/** API 키 인증 뒤 실행한다. 응답은 DB 커밋이 확인된 뒤에만 클라이언트로 내보낸다. */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {
    public static final String CLAIM = IdempotencyFilter.class.getName() + ".claim";
    private static final int MAX_JSON = 8 * 1024 * 1024;
    private final IdempotencyStore store;
    private final ProjectRepository projects;
    private final JsonMapper json;
    private final TransactionTemplate transaction;

    public IdempotencyFilter(
            IdempotencyStore store,
            ProjectRepository projects,
            JsonMapper json,
            PlatformTransactionManager manager) {
        this.store = store;
        this.projects = projects;
        this.json = json;
        transaction = new TransactionTemplate(manager);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !sdkPath(request).startsWith("/api/v1/sdk/")
                || !Set.of("POST", "PUT", "PATCH", "DELETE").contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader("Idempotency-Key");
        if (key == null) {
            if (request.getHeader("X-Request-ID") != null) {
                error(
                        response,
                        400,
                        "INVALID_IDEMPOTENCY_KEY",
                        "Use Idempotency-Key; the legacy header is not supported");
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        if (!(SecurityContextHolder.getContext().getAuthentication().getPrincipal()
                instanceof ProjectApiKeyPrincipal principal)) {
            chain.doFilter(request, response);
            return;
        }
        try {
            if (key.isEmpty()
                    || key.length() > 300
                    || key.chars().anyMatch(c -> c < 33 || c > 126)
                    || Collections.list(request.getHeaders("Idempotency-Key")).size() != 1)
                throw new IdempotencyException(
                        400,
                        "INVALID_IDEMPOTENCY_KEY",
                        "Idempotency key must be 1 to 300 visible ASCII characters");
            boolean binary =
                    sdkPath(request).matches("/api/v1/sdk/runs/[0-9]+/artifacts")
                            && request.getContentType() != null
                            && MediaType.APPLICATION_OCTET_STREAM.isCompatibleWith(
                                    MediaType.parseMediaType(request.getContentType()));
            byte[] input = binary ? null : readJson(request.getInputStream());
            Object body;
            if (binary) body = List.of("binary", request.getContentLengthLong());
            else {
                try {
                    body = input.length == 0 ? null : ordered(json.readValue(input, Object.class));
                } catch (RuntimeException malformed) {
                    body = List.of("malformed", hash(input));
                }
            }
            Map<String, List<String>> query = new TreeMap<>();
            request.getParameterMap()
                    .forEach((name, values) -> query.put(name, Arrays.asList(values)));
            String fingerprint = hash(json.writeValueAsBytes(Arrays.asList(query, body)));
            String scope =
                    hash(
                            json.writeValueAsBytes(
                                    List.of(
                                            principal.apiKeyId().value(),
                                            request.getMethod(),
                                            request.getRequestURI(),
                                            key)));
            IdempotencyStore.Claim claim = store.claim(principal, scope, fingerprint);
            if (claim.replay()) {
                replay(response, claim.response(), binary ? request.getInputStream() : null);
                return;
            }
            HttpServletRequest forwarded = binary ? request : new BodyRequest(request, input);
            forwarded.setAttribute(CLAIM, claim);
            ContentCachingResponseWrapper captured = new ContentCachingResponseWrapper(response);
            try {
                if (binary) {
                    // Upload reservation/completion own short transactions; no DB transaction spans
                    // the stream.
                    chain.doFilter(forwarded, captured);
                    IdempotencyStore.Response durable = store.completed(claim);
                    if (durable != null) {
                        send(response, durable);
                        return;
                    }
                    if (captured.getStatus() == 409
                            && new String(captured.getContentAsByteArray(), StandardCharsets.UTF_8)
                                    .contains("IDEMPOTENT_REQUEST_PROCESSING")) {
                        captured.copyBodyToResponse();
                        return;
                    }
                    store.completeFailure(claim, capture(captured));
                } else {
                    transaction.executeWithoutResult(
                            status -> {
                                // Project precedes ledger in the lock order, including project
                                // deletion.
                                Project project =
                                        projects.findByIdForUpdate(principal.projectId())
                                                .orElseThrow(ProjectNotFoundException::new);
                                if (!project.getCreatedBy().equals(principal.createdBy()))
                                    throw new IdempotencyException(
                                            403, "FORBIDDEN", "Project belongs to another owner");
                                store.lock(claim);
                                try {
                                    chain.doFilter(forwarded, captured);
                                } catch (IOException | ServletException failure) {
                                    throw new ChainFailure(failure);
                                }
                                if (captured.getStatus() >= 400) status.setRollbackOnly();
                                else store.complete(claim, capture(captured));
                            });
                    if (captured.getStatus() >= 400)
                        store.completeFailure(claim, capture(captured));
                }
                captured.copyBodyToResponse();
            } catch (RuntimeException | IOException | ServletException failure) {
                IdempotencyStore.Response durable = store.completed(claim);
                if (durable != null) {
                    send(response, durable);
                    return;
                }
                if (failure instanceof IdempotencyException expected) throw expected;
                // With no committed response, retry this same identity. Never manufacture a
                // successful result.
                store.interrupted(claim);
                error(
                        response,
                        503,
                        "IDEMPOTENCY_REQUEST_INTERRUPTED",
                        "Request did not produce a committed response; retry with the same key");
            }
        } catch (IdempotencyException failure) {
            error(response, failure.status(), failure.code(), failure.getMessage());
        }
    }

    private String sdkPath(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    private byte[] readJson(InputStream input) throws IOException {
        byte[] body = input.readNBytes(MAX_JSON + 1);
        if (body.length > MAX_JSON)
            throw new IdempotencyException(
                    413, "REQUEST_TOO_LARGE", "Request body exceeds the idempotent JSON limit");
        return body;
    }

    private Object ordered(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), ordered(v)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(this::ordered).toList();
        return value;
    }

    public static String hash(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private IdempotencyStore.Response capture(ContentCachingResponseWrapper response) {
        if (response.getContentType() != null)
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        byte[] bytes = response.getContentAsByteArray();
        if (bytes.length > 64 * 1024)
            throw new IllegalStateException("Idempotent response exceeds limit");
        Map<String, String> headers = new TreeMap<>();
        for (String name : List.of("Content-Type", "Location", "Retry-After", "Allow"))
            if (response.getHeader(name) != null) headers.put(name, response.getHeader(name));
        return new IdempotencyStore.Response(
                response.getStatus(),
                json.writeValueAsString(headers),
                new String(bytes, StandardCharsets.UTF_8),
                null);
    }

    private void replay(
            HttpServletResponse response, IdempotencyStore.Response saved, InputStream binary)
            throws IOException {
        if (binary != null && saved.sha256() != null) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] block = new byte[65536];
                for (int count; (count = binary.read(block)) != -1; )
                    digest.update(block, 0, count);
                if (!HexFormat.of().formatHex(digest.digest()).equals(saved.sha256()))
                    throw IdempotencyException.mismatch();
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        send(response, saved);
    }

    private void send(HttpServletResponse response, IdempotencyStore.Response saved)
            throws IOException {
        response.resetBuffer();
        response.setStatus(saved.status());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Map<?, ?> headers = json.readValue(saved.headers(), Map.class);
        headers.forEach(
                (name, value) -> response.setHeader(String.valueOf(name), String.valueOf(value)));
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getOutputStream().write(saved.body().getBytes(StandardCharsets.UTF_8));
    }

    private void error(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.resetBuffer();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (status == 409 && code.equals("IDEMPOTENT_REQUEST_PROCESSING"))
            response.setHeader("Retry-After", "1");
        json.writeValue(response.getOutputStream(), new SecurityErrorResponse(code, message));
    }

    private static final class ChainFailure extends RuntimeException {
        ChainFailure(Exception cause) {
            super(cause);
        }
    }

    private static final class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        BodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public int read(byte[] target, int offset, int length) {
                    return input.read(target, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Synchronous SDK request");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(
                    new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
