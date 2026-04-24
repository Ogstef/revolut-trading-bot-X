-- Leverage dimension: extends (pair, strategy, interval) triple to (pair, strategy, interval, vehicle) quadruple.
-- Existing rows default to vehicle='SPOT', leverage=1 — zero behaviour change on legacy data.

ALTER TABLE trading.positions
    ADD COLUMN vehicle                 VARCHAR(10)   NOT NULL DEFAULT 'SPOT',
    ADD COLUMN leverage                SMALLINT      NOT NULL DEFAULT 1,
    ADD COLUMN collateral              DECIMAL(18,8),
    ADD COLUMN notional                DECIMAL(18,8),
    ADD COLUMN liquidation_price       DECIMAL(18,8),
    ADD COLUMN funding_fees_accrued    DECIMAL(18,8) NOT NULL DEFAULT 0,
    ADD COLUMN last_funding_at         TIMESTAMP;

ALTER TABLE trading.trades
    ADD COLUMN vehicle       VARCHAR(10)   NOT NULL DEFAULT 'SPOT',
    ADD COLUMN leverage      SMALLINT      NOT NULL DEFAULT 1,
    ADD COLUMN collateral    DECIMAL(18,8),
    ADD COLUMN funding_fees  DECIMAL(18,8) NOT NULL DEFAULT 0,
    ADD COLUMN liquidated    BOOLEAN       NOT NULL DEFAULT FALSE;

CREATE INDEX idx_positions_vehicle_pair_strat_interval
    ON trading.positions (vehicle, pair, strategy_name, interval, status);

CREATE INDEX idx_trades_vehicle_pair_strat_interval
    ON trading.trades (vehicle, pair, strategy_name, interval, closed_at);
