-- Phase 8 — Multi-pair support.
-- No schema changes needed — pair column already exists on all tables from V1.
-- Composite indexes keep queries fast as data grows across multiple pairs and strategies.

CREATE INDEX IF NOT EXISTS idx_positions_pair_strategy
    ON trading.positions (pair, strategy_name, status);

CREATE INDEX IF NOT EXISTS idx_trades_pair_strategy
    ON trading.trades (pair, strategy_name, executed_at DESC);

CREATE INDEX IF NOT EXISTS idx_signal_logs_pair_strategy
    ON trading.signal_logs (pair, strategy_name, created_at DESC);
