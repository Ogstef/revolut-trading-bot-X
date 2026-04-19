ALTER TABLE trading.trades
    ADD COLUMN entry_fee       DECIMAL(18,8) NOT NULL DEFAULT 0,
    ADD COLUMN exit_fee        DECIMAL(18,8) NOT NULL DEFAULT 0,
    ADD COLUMN entry_slippage  DECIMAL(18,8) NOT NULL DEFAULT 0,
    ADD COLUMN exit_slippage   DECIMAL(18,8) NOT NULL DEFAULT 0,
    ADD COLUMN net_pnl         DECIMAL(18,8),
    ADD COLUMN net_pnl_pct     DECIMAL(18,8);

-- Historical closed trades: zero costs, net = gross (clean break — no retroactive rewrite)
UPDATE trading.trades
SET net_pnl     = pnl,
    net_pnl_pct = pnl_pct
WHERE closed_at IS NOT NULL AND pnl IS NOT NULL;
