-- An optimistic-lock token on transfers. Run once against any database created before this
-- change.
--
-- MUST BE RUN AGAINST BOTH DATABASES. db/init/ runs only on an empty volume, and
-- db/init/test-database.sql applies schema.sql to minibank_test as well as minibank, so an
-- existing volume has two databases at the old shape and both need this:
--
--   Git Bash / sh:
--     docker compose exec -T db psql -U minibank -d minibank      < db/migrate/transfer-version.sql
--     docker compose exec -T db psql -U minibank -d minibank_test < db/migrate/transfer-version.sql
--
--   PowerShell, where "<" is a reserved operator and the line above is a parse error:
--     Get-Content db/migrate/transfer-version.sql | docker compose exec -T db psql -U minibank -d minibank
--     Get-Content db/migrate/transfer-version.sql | docker compose exec -T db psql -U minibank -d minibank_test
--
-- Skipping minibank_test leaves the SQL integration tests failing on a missing column while the
-- application runs, which reads as a code bug rather than a missed step.
--
-- Deliberately NOT in db/init/, which docker compose runs unattended on a fresh volume: that
-- directory must describe the schema, not patch it.
--
-- Why the column exists. Accounts got a version first, because that is where the measured leak
-- was: twenty concurrent transfers, one debit applied, 115 140 CZK overwritten. It does not
-- cover the transfers row, and three paths write that row without ever calling accounts.save -
-- TransferApplicationService.cancelPayment, the wrong-OTP branch and the expired-window branch -
-- so a lost race there is invisible to accounts.version. Two tabs on one WAITING_AUTH transfer:
-- a cancel committing just after an authorization writes DECLINED over SENT and blanks fee and
-- settled_at, so the row drops out of the SENT-only day total while the debit stands.
--
-- Existing rows start at 0, which is what a fresh row gets too, so a migrated database and a
-- fresh one behave identically from the first write onward. Nothing is backfilled and nothing
-- needs to be: the column carries no history, only the token for the next write.
--
-- Idempotent, which is what makes the exit code below worth reading. ADD COLUMN IF NOT EXISTS
-- skips a column that is already there without touching the versions in it, so a second run
-- over a migrated database exits zero because it succeeded, not because an error went unseen.

-- Without this psql reports success after the ALTER below fails, and the two-database run above
-- ends in two successful-looking invocations over a schema that still has no version column.
\set ON_ERROR_STOP on

ALTER TABLE transfers
    ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 0;
