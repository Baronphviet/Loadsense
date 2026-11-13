package com.loadsense.core.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="users")
public class UserAccount {
    @Id public UUID id = UUID.randomUUID();
    @Column(nullable=false, unique=true) public String username;
    @Column(name="password_hash", nullable=false) public String passwordHash;
    @Column(name="auto_ai_analyze", nullable=false) public boolean autoAiAnalyze = true;
    @Column(name="early_anomaly_enabled", nullable=false) public boolean earlyAnomalyEnabled = true;
    @Column(name="anomaly_error_rate_threshold", nullable=false) public double anomalyErrorRateThreshold = 5.0;
    @Column(name="anomaly_p95_threshold_ms", nullable=false) public int anomalyP95ThresholdMs = 1000;
    @Column(name="auto_stop_on_anomaly", nullable=false) public boolean autoStopOnAnomaly = false;
    @Column(name="created_at", nullable=false) public Instant createdAt = Instant.now();
}
