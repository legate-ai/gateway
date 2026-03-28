-- V3: Request log table
-- Persistent record of all LLM requests processed by Legate.

CREATE TABLE IF NOT EXISTS request_log (
    request_id          VARCHAR(64)     PRIMARY KEY,
    timestamp           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    virtual_key_id      VARCHAR(64),
    team_name           VARCHAR(255),
    requested_model     VARCHAR(255)    NOT NULL,
    actual_model        VARCHAR(255),
    provider            VARCHAR(64),
    input_tokens        INTEGER,
    output_tokens       INTEGER,
    estimated_cost_usd  NUMERIC(12, 8),
    total_latency_ms    BIGINT,
    upstream_latency_ms BIGINT,
    cache_hit           BOOLEAN         NOT NULL DEFAULT FALSE,
    fallback_attempts   INTEGER         NOT NULL DEFAULT 0,
    success             BOOLEAN         NOT NULL DEFAULT TRUE,
    error_code          VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_request_log_timestamp   ON request_log (timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_request_log_virtual_key ON request_log (virtual_key_id) WHERE virtual_key_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_request_log_model       ON request_log (requested_model);
CREATE INDEX IF NOT EXISTS idx_request_log_provider    ON request_log (provider);
CREATE INDEX IF NOT EXISTS idx_request_log_success     ON request_log (success, timestamp DESC);
