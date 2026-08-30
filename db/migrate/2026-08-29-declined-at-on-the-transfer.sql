-- A refused payment gets the instant it was refused. Run once against any database created
-- before this change.
--
-- MUST BE RUN AGAINST BOTH DATABASES, like every script in this directory:
--
--   Git Bash / sh:
--     docker compose exec -T db psql -U minibank -d minibank      < db/migrate/2026-08-29-declined-at-on-the-transfer.sql
--     docker compose exec -T db psql -U minibank -d minibank_test < db/migrate/2026-08-29-declined-at-on-the-transfer.sql
--
--   PowerShell, where "<" is a reserved operator and the line above is a parse error:
--     Get-Content db/migrate/2026-08-29-declined-at-on-the-transfer.sql | docker compose exec -T db psql -U minibank -d minibank
--     Get-Content db/migrate/2026-08-29-declined-at-on-the-transfer.sql | docker compose exec -T db psql -U minibank -d minibank_test
--
-- Skipping minibank_test leaves the SQL integration tests failing on a missing column while the
-- application runs, which reads as a code bug rather than a missed step. Nothing in this
-- repository recreates the test schema: MinibankSqlUowTests only TRUNCATEs.
--
-- Deliberately NOT in db/init/, which docker compose runs unattended on an empty volume: a fresh
-- database is created at the final shape by schema.sql and has nothing to alter. Mirrored into
-- db/init/schema.sql instead, which is what a fresh volume gets.
--
-- The date puts it after 2026-08-24-daily-limit-on-the-customer.sql, which is where it has to
-- run, though for a weaker reason than that script had: it reads nothing that script writes and
-- would apply cleanly on its own. The rule for this directory stands as that file states it -
-- unprefixed scripts first in any order, then the dated ones oldest first - and a dated script
-- that could have gone anywhere still goes at the end, because a reader should not have to work
-- out which of the dated ones were order-free.
--
--
-- WHAT WAS MISSING. A payment that is refused reaches a final state and the moment it reached it
-- was recorded nowhere a query can reach. transfers already carried decline_reason, so the
-- customer's history could say WHY their payment was stopped and never WHEN. Six paths reach
-- Transfer.decline: two analyst verdicts, an expired authorization window, one wrong one-time
-- code too many, the customer's own cancel, and the demo fixture. The fourth is the quiet one -
-- it is reached from inside registerFailedOtpAttempt rather than called by name, so a caller
-- counting attempts stops a payment without a line saying so anywhere.
--
-- Only the two analyst paths sit beside a fraud alert, and that
-- alert's resolved_at answers a different question anyway - when the analyst closed the case, not
-- when the payment stopped. On a payment cancelled by its own customer, or one whose window ran
-- out, there was no alert and therefore no instant of any kind.
--
--
-- WHY NOT settled_at, WHICH IS ALREADY HERE AND ALREADY NULLABLE. Because it would be false.
-- Settlement is when the money moved, which is what the word means in this trade - pacs.008 calls
-- it the interbank settlement date, camt.053 keeps booking date and value date apart from it, and
-- a rejected payment is reported by its own status message and never carries a settlement date at
-- all. Nothing would have broken: both daily totals filter on status = SENT before they read the
-- day key, so a refused row carrying an instant is never summed on either backend. The objection
-- is not mechanical. A column named settled_at holding the instant nothing settled is a name that
-- contradicts its contents, in the artifact a reader opens first, and this schema is read.
--
-- The generic rename - settled_at to finished_at, one column for whichever end a payment came to -
-- loses no information, because a payment reaches exactly one final state. It was rejected for
-- giving up a precise term for a vague one. Two columns say which end it was in their names; one
-- column says it only if the status beside it is read too.
--
--
-- NO BACKFILL, and the reason is the one that matters most here: the value does not exist.
-- Transfer.decline never took an instant, so no row in any database has ever held one, and there
-- is nothing to copy from. The status transitions were published to an observer that writes a
-- line of text through AppLogger and is read back by nothing, which is a log rather than a record.
-- created_at would be available and would be a guess: a payment created on Monday and refused on
-- Thursday would be stamped Monday, and every screen would print it as fact. NULL says the
-- instant was never kept, which is true, and every reader of this column already draws nothing
-- where there is nothing.

ALTER TABLE transfers
    ADD COLUMN IF NOT EXISTS declined_at TIMESTAMPTZ;

-- Only a refused payment may carry one, enforced against whatever writes this table without going
-- through the domain: psql, a report job, a future migration. This is the same defence the amount,
-- the currency, the fee and the dispatch state each have, and it is written the same way - the
-- predicate passes for NULL, because a CHECK admits a row whose predicate is unknown, and NULL is
-- the normal case here rather than the exception. Every row that exists when this runs carries
-- NULL, so nothing can fail on the way in.
--
-- Guarded rather than written bare: ADD CONSTRAINT has no IF NOT EXISTS, and this script is
-- expected to survive being run twice.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'transfers_declined_at_only_when_declined'
    ) THEN
        ALTER TABLE transfers
            ADD CONSTRAINT transfers_declined_at_only_when_declined
                CHECK (declined_at IS NULL OR status = 'DECLINED');
        RAISE NOTICE 'constraint transfers_declined_at_only_when_declined added';
    ELSE
        RAISE NOTICE 'constraint transfers_declined_at_only_when_declined already present, skipping';
    END IF;
END
$$;

-- What the column cannot say for the rows that were already here, stated out loud rather than
-- left for somebody to discover on a screen. These are the refused payments this database holds
-- whose instant was never recorded and now never can be.
DO $$
DECLARE
    orphaned INTEGER;
BEGIN
    SELECT COUNT(*) INTO orphaned
      FROM transfers
     WHERE status = 'DECLINED' AND declined_at IS NULL;

    IF orphaned > 0 THEN
        RAISE NOTICE '% refused payment(s) predate this column and carry no instant. This is not '
                     'backfilled: the moment was never recorded, and created_at would be a guess '
                     'printed as a fact.', orphaned;
    ELSE
        RAISE NOTICE 'no refused payments predate this column';
    END IF;
END
$$;
