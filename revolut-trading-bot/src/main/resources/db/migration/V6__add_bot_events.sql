-- Tier 1 observability — persistent audit of bot lifecycle events.
CREATE TABLE trading.bot_events (
    id          BIGSERIAL PRIMARY KEY,
    type        VARCHAR(40)  NOT NULL,
    severity    VARCHAR(10)  NOT NULL,
    pair        VARCHAR(20),
    interval    VARCHAR(10),
    strategy    VARCHAR(40),
    title       VARCHAR(200) NOT NULL,
    detail      TEXT,
    metadata    JSONB,
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_bot_events_created_at ON trading.bot_events (created_at DESC);
CREATE INDEX idx_bot_events_type       ON trading.bot_events (type, created_at DESC);
CREATE INDEX idx_bot_events_triple     ON trading.bot_events (pair, interval, strategy, created_at DESC);
