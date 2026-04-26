-- Phase 14: sentiment-driven strategies (Reddit, CryptoPanic, Combined).
-- Two new tables:
--   1. sentiment_snapshots  — one row per classified post/news item; aggregated in-memory per scoreFor() call.
--   2. llm_budget_daily     — one row per UTC day tracking Claude Haiku spend; survives process restarts.

CREATE TABLE trading.sentiment_snapshots (
    id           BIGSERIAL    PRIMARY KEY,
    pair         VARCHAR(20)  NOT NULL,          -- "BTC-EUR" / "ETH-EUR" / "SOL-EUR" / "ALL" (no pair match)
    source       VARCHAR(20)  NOT NULL,          -- REDDIT | CRYPTOPANIC  (COMBINED is computed, never stored)
    external_id  VARCHAR(100) NOT NULL,          -- Reddit post id or CryptoPanic post id
    score        DECIMAL(6,3) NOT NULL,          -- [-1.000, +1.000]
    volume       INTEGER      NOT NULL DEFAULT 1,-- per-snapshot engagement weight (upvotes/vote-total)
    sample_size  INTEGER      NOT NULL DEFAULT 1,-- how many classifier-items fed this row (1 for single-post)
    metadata     TEXT,                           -- Jackson-serialized snapshot metadata (follows BotEvent pattern)
    captured_at  TIMESTAMP    NOT NULL DEFAULT now()
);

-- One row per (post × pair) — a Reddit/CryptoPanic post mentioning multiple coins
-- expands into one row per pair so pair-scoped aggregate queries see it naturally.
ALTER TABLE trading.sentiment_snapshots
    ADD CONSTRAINT uq_sentiment_source_external_pair
    UNIQUE (source, external_id, pair);

CREATE INDEX idx_sentiment_pair_captured
    ON trading.sentiment_snapshots (pair, captured_at DESC);

CREATE INDEX idx_sentiment_source_captured
    ON trading.sentiment_snapshots (source, captured_at DESC);

CREATE INDEX idx_sentiment_pair_source_captured
    ON trading.sentiment_snapshots (pair, source, captured_at DESC);


CREATE TABLE trading.llm_budget_daily (
    day                DATE          PRIMARY KEY,         -- UTC day
    usd_spent          DECIMAL(8,4)  NOT NULL DEFAULT 0,  -- classifier spend for this day (USD)
    posts_classified   INTEGER       NOT NULL DEFAULT 0,  -- count of posts classified
    last_updated       TIMESTAMP     NOT NULL DEFAULT now()
);
