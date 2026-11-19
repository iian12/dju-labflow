package com.dju.labflow.run.artifact.application;

import com.dju.labflow.global.id.IdGenerator;
import com.dju.labflow.project.key.domain.ProjectApiKeyPrincipal;
import com.dju.labflow.run.artifact.domain.*;
import com.dju.labflow.run.artifact.domain.repository.RunArtifactRepository;
import com.dju.labflow.run.run.application.RunAccessPolicy;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.run.run.exception.RunStateConflictException;
import jakarta.persistence.EntityManager;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.Clock;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LocalArtifactService {
    private final RunAccessPolicy access;
    private final RunArtifactRepository artifacts;
    private final EntityManager em;
    private final IdGenerator ids;
    private final Clock clock;
    private final Path root;
    private final long fileLimit;
    private final long projectLimit;

    public LocalArtifactService(
            RunAccessPolicy access,
            RunArtifactRepository artifacts,
            EntityManager em,
            IdGenerator ids,
            Clock clock,
            @Value("${labflow.artifacts.directory:./data/artifacts}") String directory,
            @Value("${labflow.artifacts.max-file-bytes:1073741824}") long fileLimit,
            @Value("${labflow.artifacts.max-project-bytes:21474836480}") long projectLimit) {
        this.access = access;
        this.artifacts = artifacts;
        this.em = em;
        this.ids = ids;
        this.clock = clock;
        this.root = Path.of(directory).toAbsolutePath().normalize();
        this.fileLimit = fileLimit;
        this.projectLimit = projectLimit;
    }

    @Transactional(rollbackFor = IOException.class)
    public RunArtifact upload(
            ProjectApiKeyPrincipal principal,
            RunId runId,
            String name,
            ArtifactKind kind,
            String requestId,
            long length,
            InputStream input)
            throws IOException {
        if (length < 0) throw new ResponseStatusException(HttpStatus.LENGTH_REQUIRED);
        if (length > fileLimit) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        if (!requestId.matches("[a-zA-Z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Invalid request ID");
        var run = access.requireOwnedForUpdate(principal, runId);
        var previous = artifacts.findByRunIdAndClientRequestId(runId, requestId);
        if (previous.isPresent()) {
            var old = previous.get();
            if (old.getStatus() == ArtifactStatus.READY
                    && old.getName().equals(name)
                    && old.getKind() == kind
                    && old.getSizeBytes() == length
                    && old.getSha256().equals(copy(input, OutputStream.nullOutputStream(), length)))
                return old;
            throw new RunStateConflictException(
                    "Artifact request ID already used with different content");
        }
        run.requireRunning();
        Number used =
                (Number)
                        em.createNativeQuery(
                                        "select coalesce(sum(a.size_bytes),0) from run_artifact_entity a join run_entity r on r.id=a.run_id where r.project_id=:project")
                                .setParameter("project", principal.projectId().value())
                                .getSingleResult();
        if (length > projectLimit - used.longValue())
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "Project storage limit exceeded");
        var id = RunArtifactId.of(ids.nextId());
        var now = clock.instant();
        var artifact =
                RunArtifact.create(
                        id,
                        runId,
                        name,
                        kind,
                        "application/octet-stream",
                        length,
                        "local",
                        id.value().toString(),
                        null,
                        requestId,
                        now,
                        now.plusSeconds(86400));
        Files.createDirectories(root);
        Path path = root.resolve(id.value().toString());
        try (var out = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        public void afterCompletion(int status) {
                            if (status == STATUS_ROLLED_BACK) remove(path);
                        }
                    });
            artifact.completeUpload(length, copy(input, out, length), clock.instant());
        }
        return artifacts.save(artifact);
    }

    private static String copy(InputStream in, OutputStream out, long length) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        byte[] buffer = new byte[65536];
        long count = 0;
        for (int n; (n = in.read(buffer)) != -1; ) {
            count += n;
            if (count > length) throw new IllegalArgumentException("File exceeds declared size");
            out.write(buffer, 0, n);
            digest.update(buffer, 0, n);
        }
        if (count != length) throw new IllegalArgumentException("Incomplete file");
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void remove(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            org.slf4j.LoggerFactory.getLogger(LocalArtifactService.class)
                    .warn("Could not remove artifact {}", path, e);
        }
    }

    @org.springframework.transaction.event.TransactionalEventListener
    public void projectDeleted(LocalFilesDeleted event) {
        for (Long id : event.ids()) remove(root.resolve(id.toString()));
    }

    public record LocalFilesDeleted(java.util.List<Long> ids) {}

    public record Download(Path path, RunArtifact metadata) {}

    @Transactional(readOnly = true)
    public Download download(ProjectApiKeyPrincipal principal, RunId runId, long id) {
        access.requireOwned(principal, runId);
        var artifact =
                artifacts
                        .findById(RunArtifactId.of(id))
                        .filter(
                                a ->
                                        a.getRunId().equals(runId)
                                                && a.getStatus() == ArtifactStatus.READY
                                                && a.getBucket().equals("local"))
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new Download(root.resolve(Long.toString(id)), artifact);
    }
}
