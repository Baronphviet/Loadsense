package com.loadsense.core.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="mock_data_sets")
public class MockDataSet {
    @Id public UUID id = UUID.randomUUID();
    @Column(name="test_config_id", nullable=false) public UUID testConfigId;
    @Column(nullable=false) public String name;
    @Column(name="schema_json", columnDefinition="TEXT") public String schemaJson;
    @Column(name="payloads_json", nullable=false, columnDefinition="TEXT") public String payloadsJson;
    @Column(name="created_at", nullable=false) public Instant createdAt = Instant.now();
}
