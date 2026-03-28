-- V1: Virtual keys table
-- Stores bcrypt-hashed virtual API keys with associated permissions.

CREATE TABLE IF NOT EXISTS virtual_keys (
    key_id          VARCHAR(64)  PRIMARY KEY,
    key_hash        VARCHAR(128) NOT NULL UNIQUE,   -- SHA-256 hex of the plaintext key
    team_name       VARCHAR(255) NOT NULL,
    allowed_models  TEXT[]       NOT NULL DEFAULT '{}',
    denied_models   TEXT[]       NOT NULL DEFAULT '{}',
    rate_limits     JSONB,       -- nullable: { "requestsPerMinute": N, "tokensPerDay": N }
    spend_limits    JSONB,       -- nullable: { "dailyLimitUsd": "10.00", "monthlyLimitUsd": "100.00" }
    metadata        JSONB        NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    revoked         BOOLEAN      NOT NULL DEFAULT FALSE,
    revoked_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_virtual_keys_hash    ON virtual_keys (key_hash)    WHERE NOT revoked;
CREATE INDEX IF NOT EXISTS idx_virtual_keys_team    ON virtual_keys (team_name);
CREATE INDEX IF NOT EXISTS idx_virtual_keys_created ON virtual_keys (created_at DESC);
