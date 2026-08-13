-- The sign of a stored fee, and the two creation instants the loaders already refuse to do
-- without. Run once against any database created before this change.
--
-- MUST BE RUN AGAINST BOTH DATABASES. db/init/ runs only on an empty volume, and
-- db/init/test-database.sql applies schema.sql to minibank_test as well as minibank, so an
-- existing volume has two databases at the old shape and both need this:
--
--   Git Bash / sh:
--     docker compose exec -T db psql -U minibank -d minibank      < db/migrate/fee-sign-and-creation-instants.sql
--     docker compose exec -T db psql -U minibank -d minibank_test < db/migrate/fee-sign-and-creation-instants.sql
--
--   PowerShell, where "<" is a reserved operator and the line above is a parse error:
--     Get-Content db/migrate/fee-sign-and-creation-instants.sql | docker compose exec -T db psql -U minibank -d minibank
--     Get-Content db/migrate/fee-sign-and-creation-instants.sql | docker compose exec -T db psql -U minibank -d minibank_test
--
-- Skipping minibank_test leaves the SQL integration tests failing on a constraint the schema does
-- not carry while the application runs, which reads as a code bug rather than a missed step.
--
-- Deliberately NOT in db/init/, which docker compose runs unattended on a fresh volume: that
-- directory must describe the schema, not patch it. Mirrored into db/init/schema.sql, which is
-- what a fresh volume gets.
--
-- WHY THE FEE CHECK. transfers.fee is the one stored number nothing re-checks on the way out.
-- Transfer.hydrateSettlement performs no validation, and that is deliberate - a loader that
-- refused a legacy row would make the whole store unreadable - so a fee written by hand loads on
-- both backends exactly as it was written. FeePolicy's contract says a fee is never negative,
-- and every fee this application stores came from one, so the rule held by construction and
-- nowhere else. A hand-written -25.00 on a SENT row therefore reaches the details endpoint and
-- the fraud desk through Transfer.feeFor: the receipt understates the historical debit and stops
-- reconciling with the balance movement that actually happened, which is the property the stored
-- fee exists to preserve. Same argument the amount and the currency CHECKs already make, against
-- the same writer.
--
-- NULL STAYS LEGAL, and the predicate is written so that it does. A transfer that has not settled
-- has been charged nothing yet, which is a different fact from having been charged zero, and
-- Transfer.feeFor reads the difference. CHECK (fee >= 0) is unknown rather than false on a NULL
-- row and a CHECK admits a row it cannot disprove, so no unsettled transfer is touched.
--
-- WHY THE TWO NOT NULLs. Both loaders already treat these columns as mandatory:
-- Transfer.hydrateForLoad and FraudAlert.hydrateForLoad each throw DataIntegrityException naming
-- the row rather than stamping the load instant over the gap, because a row created "when it was
-- last read" sorts first in a list that promises the newest and moves the date filters the
-- analyst's queue runs on, differently on every read. No writer inside the application can leave
-- either column empty: SqlTransferRepository stamps it unconditionally, and the NULL arm
-- SqlFraudAlertRepository still carries cannot be reached, because both FraudAlert constructors
-- stamp Instant.now() and its loader refuses a stored row without one. So the only producer of a
-- NULL is an out-of-domain writer - psql, a report job, a bad script - and what it produces is an
-- unreadable row rather than a merely wrong one.
-- On fraud_alerts the blast radius is wider than the row: SqlFraudAlertRepository loads the whole
-- queue and maps every alert in it, so ONE NULL created_at answers the analyst's queue with a 500
-- until somebody finds it by hand.
--
-- NO BACKFILL, and here that is the whole point rather than a convenience. NOT NULL on a
-- populated table fails if any row already holds NULL, and the obvious way out - COALESCE the
-- column to now() - would write a fact nobody knows into the exact column whose absence the
-- loaders refuse, dating an old payment to the afternoon of the migration. Every such row would
-- then load cleanly and lie. So the offending rows are listed and counted below and the script
-- stops on them: they are the finding, not the obstacle, and only somebody who can go and look at
-- what wrote them can say what instant belongs there. db/migrate/stored-fee-and-account-version.sql
-- refuses to backfill settled_at for the same reason and says so.
--
-- The pre-check is not decoration. PostgreSQL's own refusal names the column, the table and
-- nothing else, so an operator gets no count and no id and has to write the query below by hand
-- before anything can be decided. The block below writes both.
--
-- ORDERING. This one script is not independent of the rest of the directory the way
-- transfer-version.sql and stored-fee-and-account-version.sql are of each other: it constrains
-- transfers.fee, which db/migrate/stored-fee-and-account-version.sql adds. Run that one first on
-- a database old enough to need it, or this fails on a column that is not there yet.
--
-- Idempotent throughout. SET NOT NULL on a column that is already NOT NULL is accepted as a
-- no-op, the pre-checks are reads, and the CHECK is made idempotent explicitly by swallowing the
-- one error that means "already applied". So a second run over a migrated database exits zero
-- because it succeeded, not because an error went unseen, and an operator who cannot remember
-- whether a database has had this can find out by running it.

-- Without this psql reports success after any block below fails, and an operator chaining on the
-- exit code is told a half-migrated database is migrated.
\set ON_ERROR_STOP on

------------------------------------------------------------
-- transfers.fee: never negative, still optional
------------------------------------------------------------

-- Printed before the constraint is added so the offending rows are on screen rather than having
-- to be hunted for after the error. Silent on a clean database.
SELECT id, source_account_id, status, amount, fee
  FROM transfers
 WHERE fee < 0
 ORDER BY id;

-- ADD CONSTRAINT has no IF NOT EXISTS, so re-running is made safe by swallowing the one error
-- that means "already applied" and nothing else.
--
-- Validated against existing rows on purpose - no NOT VALID - exactly as transfers_amount_positive
-- and transfers_currency_czk are. Nothing this codebase can write violates it, so a failure here
-- means a fee was written outside the domain, and that row is a money bug that must be seen rather
-- than fenced off behind a constraint that does not apply to it. The SELECT above has already
-- listed them.
DO $$
BEGIN
    ALTER TABLE transfers ADD CONSTRAINT transfers_fee_not_negative CHECK (fee >= 0);
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

------------------------------------------------------------
-- The two creation instants
------------------------------------------------------------

-- Same treatment, and for the same reason: the rows first, then the count, then the ALTER.
SELECT id, source_account_id, status, settled_at
  FROM transfers
 WHERE created_at IS NULL
 ORDER BY id;

SELECT id, transfer_id, state
  FROM fraud_alerts
 WHERE created_at IS NULL
 ORDER BY id;

-- Stops the migration itself, with a message naming both counts, rather than letting the ALTER
-- below produce PostgreSQL's column-only complaint. Both tables are counted before either is
-- reported, so one run tells the operator the whole size of the problem instead of sending them
-- back for a second failure after they have fixed the first.
DO $$
DECLARE
    undated_transfers BIGINT;
    undated_alerts    BIGINT;
BEGIN
    SELECT count(*) INTO undated_transfers FROM transfers    WHERE created_at IS NULL;
    SELECT count(*) INTO undated_alerts    FROM fraud_alerts WHERE created_at IS NULL;

    IF undated_transfers > 0 OR undated_alerts > 0 THEN
        RAISE EXCEPTION
            'Cannot make created_at mandatory: % transfer row(s) and % fraud alert row(s) have none',
            undated_transfers, undated_alerts
            USING HINT = 'The rows are listed above. Nothing in this application writes a NULL '
                      || 'creation instant, so find what did and give each row the instant it '
                      || 'really was created at. Do not backfill now() - that dates an old row to '
                      || 'today and makes it load cleanly while lying.';
    END IF;
END $$;

ALTER TABLE transfers    ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE fraud_alerts ALTER COLUMN created_at SET NOT NULL;

------------------------------------------------------------
-- Post-condition
------------------------------------------------------------

-- Refuses to finish unless all three landed, so a half-applied run cannot look like a successful
-- one.
DO $$
BEGIN
    -- conname alone is not unique across a database, so the table is named too: a constraint of
    -- this name on some other table must not read as this one having landed.
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'transfers_fee_not_negative'
           AND conrelid = 'transfers'::regclass
    ) THEN
        RAISE EXCEPTION 'transfers_fee_not_negative was not created';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name IN ('transfers', 'fraud_alerts')
           AND column_name = 'created_at'
           AND is_nullable = 'YES'
    ) THEN
        RAISE EXCEPTION 'created_at is still nullable on at least one of the two tables';
    END IF;
END $$;
