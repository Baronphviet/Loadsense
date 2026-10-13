package com.loadsense.core.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="test_metrics")
public class TestMetric {
    @Id public UUID id = UUID.randomUUID();
    @Column(name="execution_id", nullable=false) public UUID executionId;
    @Column(name="sample_time", nullable=false) public Instant sampleTime;
    @Column(name="elapsed_seconds", nullable=false) public double elapsedSeconds;
    @Column(name="total_requests", nullable=false) public long totalRequests;
    @Column(name="failed_requests", nullable=false) public long failedRequests;
    @Column(name="throughput", nullable=false) public double throughput;
    @Column(name="avg_latency_ms", nullable=false) public double avgLatencyMs;
    @Column(name="p50_latency_ms", nullable=false) public double p50LatencyMs;
    @Column(name="p95_latency_ms", nullable=false) public double p95LatencyMs;
    @Column(name="error_rate", nullable=false) public double errorRate;
    @Column(name="active_users", nullable=false) public int activeUsers;
}
