-- Adds a closed_at timestamp to trades so callers can see exactly when a position was closed.
-- Nullable because the column is only populated when closePosition() is called;
-- open trades (exitPrice IS NULL) will always have closed_at = NULL.

ALTER TABLE trading.trades
    ADD COLUMN closed_at TIMESTAMP;
