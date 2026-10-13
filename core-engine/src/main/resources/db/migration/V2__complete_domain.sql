ALTER TABLE test_configs ADD COLUMN requests_per_second INT NOT NULL DEFAULT 100;
ALTER TABLE test_configs ADD COLUMN timeout_ms INT NOT NULL DEFAULT 5000;
ALTER TABLE test_configs ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE test_configs ALTER COLUMN target_url TYPE VARCHAR(2048);
ALTER TABLE test_configs ADD CONSTRAINT valid_load CHECK (virtual_users BETWEEN 1 AND 300 AND duration_seconds BETWEEN 1 AND 600 AND requests_per_second BETWEEN 1 AND 500 AND timeout_ms BETWEEN 100 AND 30000);
ALTER TABLE test_executions ADD COLUMN user_id UUID REFERENCES users(id);
UPDATE test_executions SET user_id = (SELECT user_id FROM test_configs WHERE test_configs.id = test_executions.test_config_id);
ALTER TABLE test_executions ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE test_executions ADD COLUMN config_snapshot TEXT NOT NULL DEFAULT '{}';
ALTER TABLE test_executions ADD COLUMN failure_reason VARCHAR(255);
ALTER TABLE test_executions ADD COLUMN total_requests BIGINT NOT NULL DEFAULT 0;
ALTER TABLE test_executions ADD COLUMN failed_requests BIGINT NOT NULL DEFAULT 0;
ALTER TABLE test_executions ADD COLUMN avg_latency_ms DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE test_executions ADD COLUMN p50_latency_ms DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE test_executions ADD COLUMN p95_latency_ms DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE test_executions ADD COLUMN throughput DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE test_executions ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE test_executions ADD CONSTRAINT valid_status CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED','STOPPED'));
CREATE TABLE mock_data_sets (
    id UUID PRIMARY KEY, test_config_id UUID NOT NULL REFERENCES test_configs(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL, schema_json TEXT, payloads_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE test_metrics (
    id UUID PRIMARY KEY, execution_id UUID NOT NULL REFERENCES test_executions(id),
    sample_time TIMESTAMP WITH TIME ZONE NOT NULL, elapsed_seconds DOUBLE PRECISION NOT NULL,
    total_requests BIGINT NOT NULL, failed_requests BIGINT NOT NULL, throughput DOUBLE PRECISION NOT NULL,
    avg_latency_ms DOUBLE PRECISION NOT NULL, p50_latency_ms DOUBLE PRECISION NOT NULL,
    p95_latency_ms DOUBLE PRECISION NOT NULL, error_rate DOUBLE PRECISION NOT NULL, active_users INT NOT NULL
);
CREATE TABLE test_reports (
    id UUID PRIMARY KEY, execution_id UUID NOT NULL UNIQUE REFERENCES test_executions(id),
    summary TEXT NOT NULL, assessment VARCHAR(255) NOT NULL, ai_status VARCHAR(255) NOT NULL DEFAULT 'DEFERRED',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_configs_owner ON test_configs(user_id, updated_at DESC);
CREATE INDEX idx_executions_owner ON test_executions(user_id, created_at DESC);
CREATE INDEX idx_executions_config ON test_executions(test_config_id);
CREATE INDEX idx_metrics_execution_time ON test_metrics(execution_id, sample_time);
CREATE INDEX idx_datasets_config ON mock_data_sets(test_config_id);
