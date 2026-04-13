-- Phase 11 — Multi-interval support.
-- Add interval column to positions, trades, and signal_logs.
-- Default '15m' tags all existing rows automatically — no data migration needed.
-- The candlesticks table already has an interval column from V1.

ALTER TABLE trading.positions
    ADD COLUMN interval VARCHAR(10) NOT NULL DEFAULT '15m';

ALTER TABLE trading.trades
    ADD COLUMN interval VARCHAR(10) NOT NULL DEFAULT '15m';

ALTER TABLE trading.signal_logs
    ADD COLUMN interval VARCHAR(10) NOT NULL DEFAULT '15m';

-- Composite indexes for the new (pair, interval, strategy) query pattern.
-- These supersede the V4 indexes for interval-scoped queries.
CREATE INDEX IF NOT EXISTS idx_positions_pair_interval_strategy
    ON trading.positions (pair, interval, strategy_name, status);

CREATE INDEX IF NOT EXISTS idx_trades_pair_interval_strategy
    ON trading.trades (pair, interval, strategy_name, executed_at DESC);

CREATE INDEX IF NOT EXISTS idx_signal_logs_pair_interval_strategy
    ON trading.signal_logs (pair, interval, strategy_name, created_at DESC);
