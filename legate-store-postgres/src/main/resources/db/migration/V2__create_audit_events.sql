-- V2: Audit events table
-- Append-only log of governance policy decisions and system events.

CREATE TABLE IF NOT EXISTS audit_events (
    event_id        VARCHAR(64)  PRIMARY KEY,
    timestamp       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    request_id      VARCHAR(64),
    virtual_key_id  VARCHAR(64),
    event_type      VARCHAR(64)  NOT NULL,
    description     TEXT         NOT NULL,
    details         JSONB        NOT NULL DEFAULT '{}'
);

CREATE INDEX IF NOT EXISTS idx_audit_timestamp     ON audit_events (timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_audit_request_id    ON audit_events (request_id) WHERE request_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_audit_virtual_key   ON audit_events (virtual_key_id) WHERE virtual_key_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_audit_event_type    ON audit_events (event_type);
CREATE INDEX IF NOT EXISTS idx_audit_ts_type       ON audit_events (timestamp DESC, event_type);
