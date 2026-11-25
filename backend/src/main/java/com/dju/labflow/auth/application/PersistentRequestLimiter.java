package com.dju.labflow.auth.application;

import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Atomic MariaDB/H2 upserts keep limits consistent across backend instances and restarts. */
@Service
public class PersistentRequestLimiter {
    private final EntityManager em;
    private final Clock clock;
    private final AtomicLong cleanupAfter = new AtomicLong();

    public PersistentRequestLimiter(EntityManager em, Clock clock) {
        this.em = em;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public void check(String namespace, String identity, int limit, Duration window) {
        if (limit < 1 || window.getSeconds() < 1)
            throw new IllegalArgumentException("Invalid request limit");
        Instant now = clock.instant();
        long bucket = Math.floorDiv(now.getEpochSecond(), window.getSeconds());
        Instant expires = Instant.ofEpochSecond((bucket + 1) * window.getSeconds());
        String key = hash(namespace + "\n" + identity + "\n" + bucket);
        em.createNativeQuery(
                        "insert into auth_request_bucket (bucket_key, expires_at, request_count) "
                                + "values (:key, :expires, 1) on duplicate key update request_count = least(request_count + 1, :ceiling)")
                .setParameter("key", key)
                .setParameter("expires", expires)
                .setParameter("ceiling", (long) limit + 1)
                .executeUpdate();
        Number used =
                (Number)
                        em.createNativeQuery(
                                        "select request_count from auth_request_bucket where bucket_key = :key")
                                .setParameter("key", key)
                                .getSingleResult();
        long previous = cleanupAfter.get();
        if (now.getEpochSecond() >= previous
                && cleanupAfter.compareAndSet(previous, now.getEpochSecond() + 60))
            em.createNativeQuery("delete from auth_request_bucket where expires_at <= :now")
                    .setParameter("now", now)
                    .executeUpdate();
        if (used.longValue() > limit)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests");
    }

    private String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
