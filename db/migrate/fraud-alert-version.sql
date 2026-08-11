-- An optimistic-lock token on fraud_alerts. Run once against any database created before this
-- change.
--
-- MUST BE RUN AGAINST BOTH DATABASES. db/init/ runs only on an empty volume, and
-- db/init/test-database.sql applies schema.sql to minibank_test as well as minibank, so an
-- existing volume has two databases at the old shape and both need this:
--
--   Git Bash / sh:
--     docker compose exec -T db psql -U minibank -d minibank      < db/migrate/fraud-alert-version.sql
--     docker compose exec -T db psql -U minibank -d minibank_test < db/migrate/fraud-alert-version.sql
--
--   PowerShell, where "<" is a reserved operator and the line above is a parse error:
--     Get-Content db/migrate/fraud-alert-version.sql | docker compose exec -T db psql -U minibank -d minibank
--     Get-Content db/migrate/fraud-alert-version.sql | docker compose exec -T db psql -U minibank -d minibank_test
--
-- Skipping minibank_test leaves the SQL integration tests failing on a missing column while the
-- application runs, which reads as a code bug rather than a missed step.
--
-- Deliberately NOT in db/init/, which docker compose runs unattended on a fresh volume: that
-- directory must describe the schema, not patch it. Mirrored into db/init/schema.sql, which is
-- what a fresh volume gets.
--
-- Why the column exists. Accounts got a version first, then transfers. Neither covers this row.
-- FraudAlert's own guards - approve refuses anything but NEW, markSuspicious refuses an alert
-- already SUSPICIOUS - are checked against each transaction's in-memory snapshot, and the write
-- is deferred to commit under READ COMMITTED, so they see nothing another transaction is doing.
-- What was catching these races was transfers.version, and only by accident: it fires when both
-- analysts happen to write the transfers row too. FraudApplicationService skips that write when
-- the payment is already SENT or DECLINED, and its REQUEST_CONFIRMATION route is a deliberate
-- no-op on both aggregates yet still saves the alert. On those paths the upsert assigned every
-- column with no guard at all and the last commit won.
--
-- Two reachable outcomes, both bad. A concurrent APPROVE overwrites a DECLINE, filing confirmed
-- fraud as OK. And an annotation that loaded the alert as NEW, committing after a decision,
-- writes state = NEW, decision = NULL and resolved_at = NULL back over the verdict - reopening a
-- decided alert. That one is the sharp end: authorizePayment gates on the transfer's status and
-- skips the risk re-check once any alert row exists, so a reopened NEW alert sits on a
-- confirmable payment and nothing will hold it again.
--
-- The JSON backend was never exposed to any of this: JsonUnitOfWork holds the store lock for the
-- whole transaction, so a read and a write there cannot interleave.
--
-- Existing rows start at 0, which is what a fresh row gets too, so a migrated database and a
-- fresh one behave identically from the first write onward. Nothing is backfilled and nothing
-- needs to be: the column carries no history, only the token for the next write.
--
-- Idempotent on purpose, and that is the deliberate half of the choice the post-condition below
-- makes: ADD COLUMN IF NOT EXISTS lets a second run pass quietly, because an operator who cannot
-- remember whether a database has had this must be able to find out by running it. Failing loudly
-- on an already-migrated database would buy nothing here - the column either exists at the right
-- shape or it does not, and the block at the end is what asserts that either way. Re-running it
-- resets no version: ADD COLUMN IF NOT EXISTS leaves an existing column and its values alone.

-- Without this psql reports success after the post-condition below fails, and an operator
-- chaining on the exit code is told a half-migrated database is migrated.
\set ON_ERROR_STOP on

-- NOT NULL DEFAULT 0 on a populated table. PostgreSQL 11 and later store the default in the
-- catalogue instead of rewriting every row, and this project runs postgres:14, so this is a
-- metadata-only change however many alerts exist.
ALTER TABLE fraud_alerts ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 0;

-- Refuses to finish if the column is not there, so a half-applied run cannot look like a
-- successful one.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_name = 'fraud_alerts'
           AND column_name = 'version'
    ) THEN
        RAISE EXCEPTION 'fraud_alerts.version was not created';
    END IF;
END $$;
