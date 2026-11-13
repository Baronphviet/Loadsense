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
    @Column(name="ai_analysis", columnDefinition="TEXT") public String aiAnalysis;
    @Column(name="ai_model", length=120) public String aiModel;
    @Column(name="ai_analyzed_at") public Instant aiAnalyzedAt;
    @Column(name="created_at", nullable=false) public Instant createdAt = Instant.now();
}
