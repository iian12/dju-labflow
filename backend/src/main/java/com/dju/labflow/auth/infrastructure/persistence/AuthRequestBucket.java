package com.dju.labflow.auth.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;

/** Short-lived shared counters. Keys are SHA-256 hashes; IPs and emails are not stored. */
@Entity
@Table(
        name = "auth_request_bucket",
        indexes = @Index(name = "idx_auth_bucket_expiry", columnList = "expires_at"))
public class AuthRequestBucket {
    @Id
    @Column(name = "bucket_key", length = 64)
    private String key;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "request_count", nullable = false)
    private long requestCount;

    protected AuthRequestBucket() {}
}
