package com.dju.labflow.run.artifact.application;

import com.dju.labflow.project.key.domain.ProjectApiKeyPrincipal;
import com.dju.labflow.run.artifact.domain.*;
import com.dju.labflow.run.artifact.domain.repository.RunArtifactRepository;
import com.dju.labflow.run.run.application.RunAccessPolicy;
import com.dju.labflow.run.run.domain.RunId;
import com.dju.labflow.sdk.idempotency.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LocalArtifactService {
    private final RunAccessPolicy access;
    private final RunArtifactRepository artifacts;
    private final LocalArtifactTransactions uploads;
    private final Path root;
    private final long fileLimit;
    private final long projectLimit;

    public LocalArtifactService(
            RunAccessPolicy access,
            RunArtifactRepository artifacts,
            LocalArtifactTransactions uploads,
            @Value("${labflow.artifacts.directory:./data/artifacts}") String directory,
            @Value("${labflow.artifacts.max-file-bytes:1073741824}") long fileLimit,
            @Value("${labflow.artifacts.max-project-bytes:21474836480}") long projectLimit) {
        this.access = access;
        this.artifacts = artifacts;
        this.uploads = uploads;
        this.root = Path.of(directory).toAbsolutePath().normalize();
        this.fileLimit = fileLimit;
        this.projectLimit = projectLimit;
    }

    @Transactional(propagation = Propagation.NEVER)
    public RunArtifact upload(
            ProjectApiKeyPrincipal principal,
            RunId runId,
            String name,
            ArtifactKind kind,
            IdempotencyStore.Claim claim,
            long length,
            InputStream input)
            throws IOException {
        if (length < 0) throw new ResponseStatusException(HttpStatus.LENGTH_REQUIRED);
        if (length > fileLimit) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        if (claim == null) throw new IllegalArgumentException("Idempotency-Key is required");
        RunArtifact reserved =
                uploads.reserve(principal, runId, name, kind, claim, length, projectLimit);
        if (reserved.getStatus() == ArtifactStatus.READY) {
            return uploads.complete(
                    principal,
                    reserved,
                    copy(input, OutputStream.nullOutputStream(), length),
                    claim);
        }
        Path path = root.resolve(reserved.getId().value().toString());
        Path partial = root.resolve(reserved.getId().value() + ".part");
        Path lockPath = root.resolve(".locks").resolve(reserved.getId().value().toString());
        Files.createDirectories(lockPath.getParent());
        boolean locked = false, retainFiles = false;
        try (FileChannel channel =
                FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException busy) {
                throw IdempotencyException.processing();
            }
            if (lock == null) throw IdempotencyException.processing();
            locked = true;
            try (lock) {
                boolean completing = false;
                try {
                    Files.createDirectories(root);
                    // A crashed writer's OS lock is released. Reuse its reserved artifact.
                    remove(partial);
                    remove(path);
                    String sha256;
                    try (OutputStream out =
                            Files.newOutputStream(partial, StandardOpenOption.CREATE_NEW)) {
                        sha256 = copy(input, out, length);
                    }
                    Files.move(partial, path, StandardCopyOption.ATOMIC_MOVE);
                    completing = true;
                    retainFiles = true;
                    RunArtifact completed = uploads.complete(principal, reserved, sha256, claim);
                    return completed;
                } catch (IOException | RuntimeException | Error failure) {
                    if (completing) {
                        // A commit acknowledgement can be lost after READY and the response were
                        // saved.
                        // Keep the published file if the database cannot confirm its outcome.
                        try {
                            java.util.Optional<RunArtifact> committed =
                                    uploads.committedArtifact(reserved, claim);
                            if (committed.isPresent()) return committed.get();
                            retainFiles = false;
                        } catch (RuntimeException unavailable) {
                            failure.addSuppressed(unavailable);
                            throw failure;
                        }
                    }
                    // Cleanup stays under the same OS lock, so a resumed writer cannot publish
                    // between deletion of the file and cancellation of its reservation.
                    remove(partial);
                    remove(path);
                    try {
                        uploads.discard(principal, reserved);
                    } catch (RuntimeException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                    throw failure;
                }
            }
        } finally {
            if (locked && !retainFiles) {
                remove(lockPath);
                removeEmptyLockDirectory();
            }
        }
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
        for (Long id : event.ids()) {
            remove(root.resolve(id.toString()));
            remove(root.resolve(id + ".part"));
            remove(root.resolve(".locks").resolve(id.toString()));
        }
        removeEmptyLockDirectory();
    }

    private void removeEmptyLockDirectory() {
        Path directory = root.resolve(".locks");
        try (java.util.stream.Stream<Path> paths = Files.list(directory)) {
            if (paths.findAny().isEmpty()) Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            /* Another upload may own or create a lock concurrently. */
        }
    }

    public record LocalFilesDeleted(java.util.List<Long> ids) {}

    public record Download(Path path, RunArtifact metadata) {}

    @Transactional(readOnly = true)
    public Download download(ProjectApiKeyPrincipal principal, RunId runId, long id) {
        access.requireOwned(principal, runId);
        return localDownload(runId, id);
    }

    /** Caller must authorize read access to this run. */
    public Download localDownload(RunId runId, long id) {
        RunArtifact artifact =
                artifacts
                        .findById(RunArtifactId.of(id))
                        .filter(
                                a ->
                                        a.getRunId().equals(runId)
                                                && a.getStatus() == ArtifactStatus.READY
                                                && a.getBucket().equals("local"))
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Path path = root.resolve(Long.toString(id));
        if (!Files.isRegularFile(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return new Download(path, artifact);
    }
}
