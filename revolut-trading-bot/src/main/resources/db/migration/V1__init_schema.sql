CREATE SCHEMA IF NOT EXISTS trading;

-- Positions table
CREATE TABLE trading.positions (
    id              BIGSERIAL PRIMARY KEY,
    pair            VARCHAR(20)     NOT NULL,
    side            VARCHAR(10)     NOT NULL,
    entry_price     DECIMAL(18, 8)  NOT NULL,
    quantity        DECIMAL(18, 8)  NOT NULL,
    take_profit     DECIMAL(18, 8)  NOT NULL,
    stop_loss       DECIMAL(18, 8)  NOT NULL,
    status          VARCHAR(10)     NOT NULL DEFAULT 'OPEN',
    signal_reason   TEXT,
    opened_at       TIMESTAMP       NOT NULL DEFAULT NOW(),
    closed_at       TIMESTAMP
);

CREATE INDEX idx_positions_status ON trading.positions (status);
CREATE INDEX idx_positions_pair ON trading.positions (pair);

-- Trades table
CREATE TABLE trading.trades (
    id              BIGSERIAL PRIMARY KEY,
    position_id     BIGINT          REFERENCES trading.positions(id),
    pair            VARCHAR(20)     NOT NULL,
    side            VARCHAR(10)     NOT NULL,
    entry_price     DECIMAL(18, 8)  NOT NULL,
    exit_price      DECIMAL(18, 8),
    quantity        DECIMAL(18, 8)  NOT NULL,
    pnl             DECIMAL(18, 8),
    pnl_pct         DECIMAL(18, 8),
    exit_reason     VARCHAR(20),
    trading_mode    VARCHAR(10)     NOT NULL DEFAULT 'PAPER',
    executed_at     TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_trades_position_id ON trading.trades (position_id);
CREATE INDEX idx_trades_pair ON trading.trades (pair);
CREATE INDEX idx_trades_executed_at ON trading.trades (executed_at);

-- Candlesticks table
CREATE TABLE trading.candlesticks (
    id              BIGSERIAL PRIMARY KEY,
    pair            VARCHAR(20)     NOT NULL,
    interval        VARCHAR(10)     NOT NULL,
    open_price      DECIMAL(18, 8)  NOT NULL,
    high_price      DECIMAL(18, 8)  NOT NULL,
    low_price       DECIMAL(18, 8)  NOT NULL,
    close_price     DECIMAL(18, 8)  NOT NULL,
    volume          DECIMAL(18, 8)  NOT NULL,
    timestamp       TIMESTAMP       NOT NULL
);

CREATE UNIQUE INDEX idx_candlesticks_unique ON trading.candlesticks (pair, interval, timestamp);

-- Signal logs table
CREATE TABLE trading.signal_logs (
    id              BIGSERIAL PRIMARY KEY,
    pair            VARCHAR(20)     NOT NULL,
    signal_type     VARCHAR(10)     NOT NULL,
    confidence      DECIMAL(5, 2),
    reason          TEXT,
    ema_short       DECIMAL(18, 8),
    ema_long        DECIMAL(18, 8),
    rsi             DECIMAL(18, 8),
    current_price   DECIMAL(18, 8),
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_signal_logs_pair ON trading.signal_logs (pair);
CREATE INDEX idx_signal_logs_created_at ON trading.signal_logs (created_at);
