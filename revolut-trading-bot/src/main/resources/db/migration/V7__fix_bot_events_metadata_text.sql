-- Downgrade bot_events.metadata from jsonb to text.
-- Hibernate binds our String-typed field as VARCHAR, which Postgres refuses to implicit-cast into
-- jsonb. The column only stores small audit blobs (e.g. config-patch JSON as a raw string) — we
-- don't need jsonb's validation or indexing, and every existing jsonb value is representable as text.
ALTER TABLE trading.bot_events ALTER COLUMN metadata TYPE text;
