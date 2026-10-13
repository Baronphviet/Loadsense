package com.loadsense.core.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="test_configs")
public class TestConfig {
    @Id public UUID id = UUID.randomUUID();
    @Column(name="user_id", nullable=false) public UUID userId;
    @Column(nullable=false) public String name;
    @Column(name="target_url", nullable=false, length=2048) public String targetUrl;
    @Column(name="http_method", nullable=false) public String httpMethod;
    @Column(name="request_headers", columnDefinition="TEXT") public String requestHeaders;
    @Column(name="request_body", columnDefinition="TEXT") public String requestBody;
    @Column(name="virtual_users", nullable=false) public int virtualUsers;
    @Column(name="duration_seconds", nullable=false) public int durationSeconds;
    @Column(name="requests_per_second", nullable=false) public int requestsPerSecond;
    @Column(name="timeout_ms", nullable=false) public int timeoutMs;
    @Column(name="created_at", nullable=false) public Instant createdAt = Instant.now();
    @Column(name="updated_at", nullable=false) public Instant updatedAt = Instant.now();
}
