ALTER TABLE users
    ADD COLUMN auto_ai_analyze BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN early_anomaly_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN anomaly_error_rate_threshold DOUBLE PRECISION NOT NULL DEFAULT 5.0,
    ADD COLUMN anomaly_p95_threshold_ms INT NOT NULL DEFAULT 1000,
    ADD COLUMN auto_stop_on_anomaly BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE test_executions
    ADD COLUMN mock_data_set_id UUID REFERENCES mock_data_sets(id) ON DELETE SET NULL,
    ADD COLUMN error_breakdown TEXT NOT NULL DEFAULT '{}',
    ADD COLUMN anomaly_warning TEXT;

ALTER TABLE test_reports
    ADD COLUMN ai_model VARCHAR(120),
    ADD COLUMN ai_analyzed_at TIMESTAMP WITH TIME ZONE;
