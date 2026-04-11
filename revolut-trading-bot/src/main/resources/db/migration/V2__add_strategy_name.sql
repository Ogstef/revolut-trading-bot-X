-- Phase 7: Multi-strategy parallel paper trading.
-- Every signal, position, and trade must carry the strategy that produced it
-- so we can compare performance across strategies side-by-side.
-- Default 'EMA_CROSSOVER' keeps existing rows consistent without data migration.

ALTER TABLE trading.signal_logs
    ADD COLUMN strategy_name VARCHAR(50) NOT NULL DEFAULT 'EMA_CROSSOVER';

ALTER TABLE trading.positions
    ADD COLUMN strategy_name VARCHAR(50) NOT NULL DEFAULT 'EMA_CROSSOVER';

ALTER TABLE trading.trades
    ADD COLUMN strategy_name VARCHAR(50) NOT NULL DEFAULT 'EMA_CROSSOVER';

-- Index for the most common query pattern: filter by strategy + status
CREATE INDEX idx_positions_strategy_status ON trading.positions (strategy_name, status);
CREATE INDEX idx_trades_strategy ON trading.trades (strategy_name);
CREATE INDEX idx_signal_logs_strategy ON trading.signal_logs (strategy_name);
