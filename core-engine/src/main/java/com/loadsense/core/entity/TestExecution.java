package com.loadsense.core.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="test_executions")
public class TestExecution {
    @Id public UUID id = UUID.randomUUID();
    @Column(name="test_config_id", nullable=false) public UUID testConfigId;
    @Column(name="user_id", nullable=false) public UUID userId;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public ExecutionStatus status;
    @Column(name="start_time") public Instant startTime;
    @Column(name="end_time") public Instant endTime;
    @Column(name="created_at", nullable=false) public Instant createdAt = Instant.now();
    @Column(name="config_snapshot", nullable=false, columnDefinition="TEXT") public String configSnapshot;
    @Column(name="failure_reason") public String failureReason;
    @Column(name="total_requests", nullable=false) public long totalRequests;
    @Column(name="failed_requests", nullable=false) public long failedRequests;
    @Column(name="avg_latency_ms", nullable=false) public double avgLatencyMs;
    @Column(name="p50_latency_ms", nullable=false) public double p50LatencyMs;
    @Column(name="p95_latency_ms", nullable=false) public double p95LatencyMs;
    @Column(name="throughput", nullable=false) public double throughput;
    @Version public long version;
}
