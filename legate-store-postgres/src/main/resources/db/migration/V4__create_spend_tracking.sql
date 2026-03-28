-- V4: Spend tracking table
-- Accumulates USD spend per virtual key per day.

CREATE TABLE IF NOT EXISTS spend_tracking (
    virtual_key_id      VARCHAR(64)     NOT NULL,
    date                DATE            NOT NULL,
    daily_spend_usd     NUMERIC(14, 8)  NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_spend_tracking PRIMARY KEY (virtual_key_id, date)
);

CREATE INDEX IF NOT EXISTS idx_spend_key_date ON spend_tracking (virtual_key_id, date DESC);
CREATE INDEX IF NOT EXISTS idx_spend_date     ON spend_tracking (date DESC);

-- Summary view: current month spend per key
CREATE OR REPLACE VIEW spend_monthly_summary AS
SELECT
    virtual_key_id,
    DATE_TRUNC('month', date) AS month,
    SUM(daily_spend_usd) AS monthly_spend_usd
FROM spend_tracking
GROUP BY virtual_key_id, DATE_TRUNC('month', date);
