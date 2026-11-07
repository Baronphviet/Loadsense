CREATE TABLE approved_target_hosts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    host VARCHAR(253) NOT NULL,
    CONSTRAINT unique_user_target_host UNIQUE (user_id, host)
);
