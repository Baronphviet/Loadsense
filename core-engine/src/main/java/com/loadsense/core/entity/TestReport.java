package com.loadsense.core.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="test_reports")
public class TestReport {
    @Id public UUID id = UUID.randomUUID();
    @Column(name="execution_id", nullable=false, unique=true) public UUID executionId;
    @Column(name="summary", nullable=false, columnDefinition="TEXT") public String summary;
    @Column(name="assessment", nullable=false) public String assessment;
    @Column(name="ai_status", nullable=false) public String aiStatus = "DEFERRED";
    @Column(name="created_at", nullable=false) public Instant createdAt = Instant.now();
}
