package com.loadsense.core.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="test_suites")
public class TestSuite {
    @Id public UUID id = UUID.randomUUID();
    @Column(name="user_id", nullable=false) public UUID userId;
    @Column(nullable=false, length=160) public String name;
    @Column(name="target_url", nullable=false, length=2048) public String targetUrl;
    @Column(name="plan_yaml", nullable=false, columnDefinition="TEXT") public String planYaml;
    @Column(name="cases_json", nullable=false, columnDefinition="TEXT") public String casesJson;
    @Column(name="total_cases", nullable=false) public int totalCases;
    @Column(name="passed_cases", nullable=false) public int passedCases;
    @Column(name="failed_cases", nullable=false) public int failedCases;
    @Column(name="running_cases", nullable=false) public int runningCases;
    @Column(name="last_run_at") public Instant lastRunAt;
    @Column(name="created_at", nullable=false) public Instant createdAt = Instant.now();
    @Column(name="updated_at", nullable=false) public Instant updatedAt = Instant.now();
}
