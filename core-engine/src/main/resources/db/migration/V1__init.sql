-- V1__init.sql
CREATE TABLE users (
    id UUID PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE test_configs (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(255) NOT NULL,
    target_url VARCHAR(255) NOT NULL,
    http_method VARCHAR(10) NOT NULL,
    request_headers TEXT,
    request_body TEXT,
    virtual_users INT NOT NULL,
    duration_seconds INT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE test_executions (
    id UUID PRIMARY KEY,
    test_config_id UUID NOT NULL REFERENCES test_configs(id),
    status VARCHAR(50) NOT NULL, -- PENDING, RUNNING, COMPLETED, FAILED, STOPPED
    start_time TIMESTAMP WITH TIME ZONE,
    end_time TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
