-- Phase 13 revert: Revolut removed leverage trading capability.
-- Deletes all non-SPOT rows, then drops every leverage column added by V9.

DELETE FROM trading.trades    WHERE vehicle <> 'SPOT';
DELETE FROM trading.positions WHERE vehicle <> 'SPOT';

DROP INDEX IF EXISTS trading.idx_positions_vehicle_pair_strat_interval;
DROP INDEX IF EXISTS trading.idx_trades_vehicle_pair_strat_interval;

ALTER TABLE trading.positions
    DROP COLUMN last_funding_at,
    DROP COLUMN funding_fees_accrued,
    DROP COLUMN liquidation_price,
    DROP COLUMN notional,
    DROP COLUMN collateral,
    DROP COLUMN leverage,
    DROP COLUMN vehicle;

ALTER TABLE trading.trades
    DROP COLUMN liquidated,
    DROP COLUMN funding_fees,
    DROP COLUMN collateral,
    DROP COLUMN leverage,
    DROP COLUMN vehicle;
