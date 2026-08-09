-- An index for the query the daily ceiling runs, and the removal of three that serve nothing.
--
-- Run by hand against each database:
--   psql -U minibank -d minibank      -f db/migrate/transfer-daily-total-index.sql
--   psql -U minibank -d minibank_test -f db/migrate/transfer-daily-total-index.sql
--
-- Mirrored into db/init/schema.sql.
--
-- The query this is for became hot when the daily limit started counting the day's total rather
-- than one payment. It runs on every payment creation and again on every authorization, in two
-- variants - the account's whole day, and the day's total to one payee - and both open with the
-- same predicates:
--
--     WHERE source_account_id = ?
--       AND status = ?
--       AND currency = ?
--       AND COALESCE(settled_at, created_at) >= ? AND < ?
--
-- Until now the only index on transfers was on source_account_id alone, so every one of those
-- became a scan of one account's whole history to add up one day of it.
--
-- currency is deliberately not in the index. Every row this application writes is CZK - the
-- FeePolicy contract says so and no writer produces anything else - so the column has one value
-- and no selectivity to offer. Including it would widen every index entry to let the planner
-- skip nothing.
--
-- COALESCE(settled_at, created_at) is an expression rather than a column, which is why this is
-- an expression index. The expression has to be written exactly as the query writes it or the
-- planner will not match it.
--
-- Three indexes go. idx_transfers_source_account is subsumed: this one leads with the same
-- column, so anything that used the old one uses this. idx_transfers_beneficiary and
-- idx_fraud_alerts_state were never used at all - beneficiary_id appears in no WHERE clause in
-- the codebase, only in an upsert's EXCLUDED list, and fraud_alerts.state is filtered in Java
-- after the whole table has been loaded, which is a separate item and not one an index helps.

\set ON_ERROR_STOP on

CREATE INDEX IF NOT EXISTS idx_transfers_daily_total
    ON transfers (source_account_id, status, (COALESCE(settled_at, created_at)));

DROP INDEX IF EXISTS idx_transfers_source_account;
DROP INDEX IF EXISTS idx_transfers_beneficiary;
DROP INDEX IF EXISTS idx_fraud_alerts_state;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_transfers_daily_total') THEN
        RAISE EXCEPTION 'idx_transfers_daily_total was not created';
    END IF;
END $$;
