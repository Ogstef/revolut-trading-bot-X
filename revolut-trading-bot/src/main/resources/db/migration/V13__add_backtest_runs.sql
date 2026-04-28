-- Persistent record of every backtest run.
-- One row per run; trades, equity curve, stats, and override params stored as JSONB
-- so SQL queries can filter on e.g. (stats->>'netPnl')::numeric for analysis.

CREATE TABLE trading.backtest_runs (
    id                UUID          PRIMARY KEY,
    pair              VARCHAR(20)   NOT NULL,
    strategy          VARCHAR(40)   NOT NULL,
    interval          VARCHAR(10)   NOT NULL,
    start_date        TIMESTAMP     NOT NULL,
    end_date          TIMESTAMP     NOT NULL,
    starting_balance  DECIMAL(18,8) NOT NULL,
    params            JSONB,
    stats             JSONB         NOT NULL,
    trades            JSONB         NOT NULL,
    equity_curve      JSONB         NOT NULL,
    label             VARCHAR(120),
    notes             TEXT,
    created_at        TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_backtest_runs_lookup
    ON trading.backtest_runs (pair, strategy, interval, created_at DESC);
