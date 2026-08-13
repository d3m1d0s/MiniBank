-- What a settled payment still owes the payment network, on the transfers row. Run once against
-- any database created before this change.
--
-- MUST BE RUN AGAINST BOTH DATABASES. db/init/ runs only on an empty volume, and
-- db/init/test-database.sql applies schema.sql to minibank_test as well as minibank, so an
-- existing volume has two databases at the old shape and both need this:
--
--   Git Bash / sh:
--     docker compose exec -T db psql -U minibank -d minibank      < db/migrate/transfer-dispatch-state.sql
--     docker compose exec -T db psql -U minibank -d minibank_test < db/migrate/transfer-dispatch-state.sql
--
--   PowerShell, where "<" is a reserved operator and the line above is a parse error:
--     Get-Content db/migrate/transfer-dispatch-state.sql | docker compose exec -T db psql -U minibank -d minibank
--     Get-Content db/migrate/transfer-dispatch-state.sql | docker compose exec -T db psql -U minibank -d minibank_test
--
-- Skipping minibank_test leaves the SQL integration tests failing on a missing column while the
-- application runs, which reads as a code bug rather than a missed step.
--
-- Deliberately NOT in db/init/, which docker compose runs unattended on a fresh volume: that
-- directory must describe the schema, not patch it. Mirrored into db/init/schema.sql, which is
-- what a fresh volume gets.
--
-- WHY THE COLUMN EXISTS. A payment that leaves this bank is handed to the payment network, and
-- until now that happened inside the open unit of work, before the commit. Every row write is
-- deferred to commit, so a commit that then failed - a stale account version, a transfer another
-- transaction had changed, a driver error - rolled the debit and the SENT status back while the
-- network already had the payment. The customer was told 409, that nothing had been charged, and
-- invited to submit again, which handed the same payment over a second time. The column records
-- the obligation on the same row and in the same transaction as the debit, so the two commit
-- together or neither does, and the dispatch itself moves out of the transaction.
--
-- Three values and one of them is NULL. PENDING means this payment has settled out of the bank
-- and no gateway has been handed it. DISPATCHED means one has. NULL means the payment owes the
-- network nothing at all: every intra-bank transfer, which is credited to a destination account
-- in the same transaction and reaches no gateway, and everything that has not settled.
--
-- NO BACKFILL, and here that is the safety property rather than a convenience. Every settled
-- external transfer already in a live database was dispatched by the old code BEFORE its
-- transaction committed - the send happened first, so a committed SENT row is itself the evidence
-- that the network was handed that payment. Marking those rows PENDING would therefore be a
-- statement the data contradicts, and the first startup after this change would sweep them up and
-- send real payments a second time. That is a worse defect than the one being fixed, because it
-- fires on every historical row at once rather than on a commit that happens to fail. So existing
-- rows keep NULL, which reads as "owes nothing", which is exactly true of them. The column starts
-- carrying real values from the first settlement after the deployment.
--
-- What the no-backfill choice costs, stated rather than discovered: a payment that the OLD code
-- dispatched and then failed to commit is not recovered by this. It cannot be - that transaction
-- left no row anywhere, which is the whole shape of the defect - and no migration can find what
-- was never written. This closes the hole going forward; it does not audit the past.
--
-- THE CHECK. Both loaders refuse a dispatch state they cannot read rather than reading it as
-- absent, because absent is the lenient answer and lenient here means a payment silently stops
-- being one the sweep will ever hand over. This is where a value outside the two names is refused
-- at write time instead, against the same writer transfers_currency_czk and
-- transfers_fee_not_negative were added against: psql, a report job, a bad script. Deliberately
-- not NOT VALID, exactly as those two are not - existing rows are all NULL and a CHECK admits a
-- row whose predicate is unknown, so validating them costs one pass over the table and proves the
-- constraint is true of everything under it.
--
-- Idempotent throughout, and that is what makes the exit code worth reading. ADD COLUMN IF NOT
-- EXISTS leaves an existing column and its values alone, and ADD CONSTRAINT has no such clause so
-- it is made idempotent by swallowing the one error that means "already applied" and nothing
-- else. A second run over a migrated database exits zero because it succeeded, not because an
-- error went unseen, so an operator who cannot remember whether a database has had this can find
-- out by running it. Re-running dispatches nothing and marks nothing: this script writes no rows.

-- Without this psql reports success after any block below fails, and an operator chaining on the
-- exit code is told a half-migrated database is migrated.
\set ON_ERROR_STOP on

-- Nullable and with no default, which is what leaves every existing row saying it owes the
-- network nothing. PostgreSQL 11 and later add a column with no default as a metadata-only
-- change, and this project runs postgres:14, so this does not rewrite the table however many
-- transfers exist.
ALTER TABLE transfers
    ADD COLUMN IF NOT EXISTS dispatch_state VARCHAR(32);

-- Re-running is made safe by swallowing the one error that means "already applied" and nothing
-- else, the shape db/migrate/currency-is-czk.sql uses.
DO $$
BEGIN
    ALTER TABLE transfers
        ADD CONSTRAINT transfers_dispatch_state_known
        CHECK (dispatch_state IN ('PENDING', 'DISPATCHED'));
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

-- Refuses to finish unless both landed, so a half-applied run cannot look like a successful one.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'transfers'
           AND column_name = 'dispatch_state'
    ) THEN
        RAISE EXCEPTION 'transfers.dispatch_state was not created';
    END IF;

    -- conname alone is not unique across a database, so the table is named too: a constraint of
    -- this name on some other table must not read as this one having landed.
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'transfers_dispatch_state_known'
           AND conrelid = 'transfers'::regclass
    ) THEN
        RAISE EXCEPTION 'transfers_dispatch_state_known was not created';
    END IF;
END $$;
