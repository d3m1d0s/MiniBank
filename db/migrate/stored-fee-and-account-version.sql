-- The fee a transfer was actually charged, the version that guards an account write, and five
-- columns bundled with them. Run once against any database created before this change.
--
-- MUST BE RUN AGAINST BOTH DATABASES. db/init/ runs only on an empty volume, and
-- db/init/test-database.sql applies schema.sql to minibank_test as well as minibank, so an
-- existing volume has two databases at the old shape and both need this:
--
--   Git Bash / sh:
--     docker compose exec -T db psql -U minibank -d minibank      < db/migrate/stored-fee-and-account-version.sql
--     docker compose exec -T db psql -U minibank -d minibank_test < db/migrate/stored-fee-and-account-version.sql
--
--   PowerShell, where "<" is a reserved operator and the line above is a parse error:
--     Get-Content db/migrate/stored-fee-and-account-version.sql | docker compose exec -T db psql -U minibank -d minibank
--     Get-Content db/migrate/stored-fee-and-account-version.sql | docker compose exec -T db psql -U minibank -d minibank_test
--
-- Skipping minibank_test leaves the SQL integration tests failing on a missing column while
-- the application runs, which reads as a code bug rather than a missed step. Nothing in this
-- repository recreates the test schema: MinibankSqlUowTests only TRUNCATEs.
--
-- Deliberately NOT in db/init/, which docker compose runs unattended on a fresh volume: a
-- fresh database is created at the final shape by schema.sql and has nothing to alter. Same
-- reasoning as db/reset.sql and db/migrate/hold-alerted-transfers.sql. This script and
-- db/migrate/transfer-version.sql are independent of each other and may be run in either
-- order; the filenames carry no ordering.
--
-- A migrated database is NOT byte-identical to a fresh one. ALTER TABLE appends, so the new
-- columns land at the end of each table rather than where schema.sql shows them. Types,
-- nullability, defaults and the constraint definition are identical, and nothing in this
-- codebase reads a column positionally - there is no SELECT * anywhere and every INSERT names
-- its columns - so only a pg_dump diff or a side-by-side \d can tell the two apart.
--
-- Idempotent apart from the CHECK, which is made idempotent explicitly below.
--
-- NO BACKFILL, and that is a decision rather than an omission:
--
--   transfers.fee stays NULL on every existing row. The fee those transfers were charged is
--   not recorded anywhere, so computing one now from today's FeePolicy would invent the exact
--   number this column exists to stop inventing. A NULL fee reads as "not known", and
--   Transfer.feeFor falls back to a quote from the current policy, which is what every screen
--   already showed.
--
--   transfers.settled_at stays NULL on every existing row, including SENT ones. Writing
--   created_at into it would claim those payments settled the instant they were ordered, which
--   is false for anything that went through WAITING_AUTH or a fraud review. The daily total
--   instead reads COALESCE(settled_at, created_at), so an old row keeps counting against
--   exactly the day it counted against before this change and a new row counts against the day
--   it actually settled.

-- Without this psql reports success after the DO block below fails, and an operator chaining
-- on the exit code is told a half-migrated database is migrated.
\set ON_ERROR_STOP on

ALTER TABLE accounts     ADD COLUMN IF NOT EXISTS soft_daily_threshold_czk NUMERIC(14,2);

-- NOT NULL DEFAULT 0 on a populated table. PostgreSQL 11 and later store the default in the
-- catalogue instead of rewriting every row, and this project runs postgres:14, so this is a
-- metadata-only change however many accounts exist.
ALTER TABLE accounts     ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 0;

ALTER TABLE transfers    ADD COLUMN IF NOT EXISTS fee NUMERIC(14,2);
ALTER TABLE transfers    ADD COLUMN IF NOT EXISTS message VARCHAR(140);
ALTER TABLE transfers    ADD COLUMN IF NOT EXISTS settled_at TIMESTAMPTZ;

ALTER TABLE fraud_alerts ADD COLUMN IF NOT EXISTS decided_by VARCHAR(100);

-- Printed before the constraint is added so the offending rows are on screen rather than
-- having to be hunted for after the error. Silent on a clean database.
SELECT id, source_account_id, amount, status
  FROM transfers
 WHERE amount <= 0;

-- ADD CONSTRAINT has no IF NOT EXISTS, so re-running is made safe by swallowing the one error
-- that means "already applied" and nothing else.
--
-- Validated against existing rows on purpose - no NOT VALID. Nothing this codebase can write
-- violates it (Transfer's constructor refuses a non-positive amount on both the creation path
-- and the rehydration path), so a failure here means a row was written by hand or by something
-- outside the domain, and that row is a money bug that must be seen rather than fenced off
-- behind a constraint that does not apply to it. The SELECT above has already listed them.
DO $$
BEGIN
    ALTER TABLE transfers ADD CONSTRAINT transfers_amount_positive CHECK (amount > 0);
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;
