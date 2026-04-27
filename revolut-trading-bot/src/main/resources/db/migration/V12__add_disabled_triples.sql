-- Per-(pair, strategy, interval) enable/disable.
-- Sparse table: only DISABLED triples are stored. Empty table = original behavior
-- (all triples enabled). New strategies/pairs/intervals automatically default to
-- enabled because they have no row here.

CREATE TABLE trading.disabled_triples (
    pair          VARCHAR(20)  NOT NULL,
    strategy_name VARCHAR(40)  NOT NULL,
    interval      VARCHAR(10)  NOT NULL,
    disabled_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    reason        VARCHAR(255),
    PRIMARY KEY (pair, strategy_name, interval)
);
