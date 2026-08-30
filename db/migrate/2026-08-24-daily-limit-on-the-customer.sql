-- The hard daily ceiling and the soft authorization tier move off the account and onto the
-- customer. Run once against any database created before this change.
--
-- MUST BE RUN AGAINST BOTH DATABASES, like every script in this directory:
--
--   Git Bash / sh:
--     docker compose exec -T db psql -U minibank -d minibank      < db/migrate/2026-08-24-daily-limit-on-the-customer.sql
--     docker compose exec -T db psql -U minibank -d minibank_test < db/migrate/2026-08-24-daily-limit-on-the-customer.sql
--
--   PowerShell, where "<" is a reserved operator and the line above is a parse error:
--     Get-Content db/migrate/2026-08-24-daily-limit-on-the-customer.sql | docker compose exec -T db psql -U minibank -d minibank
--     Get-Content db/migrate/2026-08-24-daily-limit-on-the-customer.sql | docker compose exec -T db psql -U minibank -d minibank_test
--
-- Skipping minibank_test leaves the SQL integration tests failing on a missing column while the
-- application runs, which reads as a code bug rather than a missed step. Nothing in this
-- repository recreates the test schema: MinibankSqlUowTests only TRUNCATEs.
--
-- Deliberately NOT in db/init/, which docker compose runs unattended on an empty volume: a fresh
-- database is created at the final shape by schema.sql and has nothing to alter. Mirrored into
-- db/init/schema.sql instead, which is what a fresh volume gets.
--
--
-- WHY THE DATE IN THE NAME. This is the first script here whose position in the run order
-- matters, and the first to carry a date. The eleven unprefixed scripts are the era before
-- ordering: each is independent of the others, stored-fee-and-account-version.sql says as much in
-- as many words about the only pair that could have collided, and any of them may be applied at
-- any time. This one is not independent. It reads accounts.soft_daily_threshold_czk, which
-- stored-fee-and-account-version.sql creates, and it then drops that column, so it has to run
-- after every unprefixed script rather than wherever the letter "d" would have put it in the
-- listing. The rule for reading this directory is therefore: apply the unprefixed files first, in
-- any order, then the dated ones oldest first. ls and every file browser sort digits ahead of
-- letters, so the dated scripts form their own block above the legacy names and a reader sees two
-- eras instead of one interleaved list.
--
--
-- WHY THE COLUMNS MOVE. A ceiling per account is a ceiling a customer with two accounts does not
-- have. The demo customer carried 40 000 on one account and 8 000 on the other, so a day's
-- spending was bounded at 48 000 rather than at either number, and the second allowance could be
-- funded by first moving money between the customer's own accounts. A limit that is escaped by
-- paying oneself bounds nothing, which is a poor foundation for an application whose subject is
-- spotting suspicious payments.
--
-- The day's total that the ceiling is checked against follows the same logic: it counts what left
-- any account the customer holds, less what only landed on another account of theirs. Without
-- that subtraction an internal move would be counted once as it leaves and once as it arrives.
-- The exclusion is by IBAN against the customer's own accounts and not by "stayed inside this
-- bank": a trusted payee may well bank here too, and money sent to them has left the customer.
--
--
-- BACKFILL OF daily_limit_czk: the largest ceiling any of the customer's accounts carried.
--
-- Not the sum, which would hand the customer exactly the stacked allowance this change removes,
-- and not the smallest, which would silently tighten the limit on the account they actually pay
-- from. The largest is the one number that takes nothing away from the account that mattered
-- while ending the stacking. For the customer in this database it is 40 000, the primary
-- account's ceiling, which is the value DemoScenario opens with and the one the demo scenario's
-- 23 200 of accumulated payments is pinned under.
--
-- A customer holding no account at all has no ceiling to carry over, and the bank has no default
-- hard limit to fall back on the way it has a default soft tier. Such a row gets 40 000, written
-- here as a literal for want of anywhere better to read it from. It is the ceiling every account
-- in this database except the retired 8 000 one already had. The alternative was to let SET NOT
-- NULL fail on a customer whose only peculiarity is owning no account.
--
--
-- WHAT IS NOT BACKFILLED: soft_daily_threshold_czk. Every customer starts with no override and
-- rides the bank-wide tier, RuleBasedRiskService.DEFAULT_SOFT_DAILY_THRESHOLD.
--
-- There is no meaning-preserving way to fold two accounts' soft tiers into one customer tier.
-- The smallest would apply the strictest account's tier to money leaving the other; the largest
-- would do the reverse; either invents a policy nobody stated. The one value in this database,
-- 3 000 on the 8 000 account, was never a policy either: it existed to give the column an
-- occupant, and under an 8 000 ceiling it made the "asks for a code" band unreachable, because
-- reaching the bank's 15 000 tier from that account meant first crossing its own 8 000 refusal.
-- Every account carrying such a value is printed below before the column goes, so an operator
-- sees what is being discarded rather than having to reconstruct it from a dump.

-- Without this psql reports success after a DO block below fails, and an operator chaining on the
-- exit code is told a half-migrated database is migrated.
\set ON_ERROR_STOP on

-- Added nullable, filled, then made NOT NULL, because a populated table cannot take a NOT NULL
-- column without a default and the correct value differs per row.
ALTER TABLE customers ADD COLUMN IF NOT EXISTS daily_limit_czk          NUMERIC(14,2);
ALTER TABLE customers ADD COLUMN IF NOT EXISTS soft_daily_threshold_czk NUMERIC(14,2);

-- The carry-over reads columns this script later drops, so it is guarded by their presence rather
-- than written at the top level: that is what makes a second run a no-op instead of an error on a
-- column that is already gone. PL/PgSQL plans a statement the first time it executes it, so the
-- UPDATE inside the branch is never looked up when the branch is not taken.
DO $$
DECLARE
    losing        RECORD;
    has_ceiling   BOOLEAN;
    has_soft_tier BOOLEAN;
BEGIN
    SELECT EXISTS (SELECT 1
                     FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name   = 'accounts'
                      AND column_name  = 'daily_limit_czk')
      INTO has_ceiling;

    SELECT EXISTS (SELECT 1
                     FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name   = 'accounts'
                      AND column_name  = 'soft_daily_threshold_czk')
      INTO has_soft_tier;

    -- The ceiling is what the carry-over reads, so its absence is what says there is nothing left
    -- to carry. A stray soft tier column surviving alone is not this branch's problem: the DROP
    -- below is unconditional and removes it.
    IF NOT has_ceiling THEN
        RAISE NOTICE 'accounts no longer carries a daily ceiling: nothing left to carry over';
        RETURN;
    END IF;

    -- accounts has carried a ceiling since the schema was created; the soft tier arrives later,
    -- with stored-fee-and-account-version.sql. The first without the second is a database that
    -- never got the unprefixed scripts, and the loop below would fail on an unknown column. Said
    -- here instead, in the terms an operator can act on.
    IF NOT has_soft_tier THEN
        RAISE EXCEPTION
            'accounts has a daily ceiling but no soft tier column: run the unprefixed scripts in db/migrate first';
    END IF;

    FOR losing IN
        SELECT id, iban, customer_id, soft_daily_threshold_czk
          FROM accounts
         WHERE soft_daily_threshold_czk IS NOT NULL
         ORDER BY id
    LOOP
        RAISE NOTICE
            'account % (%) of customer % loses its own soft tier of %: its owner now rides the bank-wide one',
            losing.id, losing.iban, losing.customer_id, losing.soft_daily_threshold_czk;
    END LOOP;

    UPDATE customers c
       SET daily_limit_czk = COALESCE((SELECT MAX(a.daily_limit_czk)
                                         FROM accounts a
                                        WHERE a.customer_id = c.id), 40000)
     WHERE c.daily_limit_czk IS NULL;
END $$;

ALTER TABLE customers ALTER COLUMN daily_limit_czk SET NOT NULL;

ALTER TABLE accounts DROP COLUMN IF EXISTS daily_limit_czk;
ALTER TABLE accounts DROP COLUMN IF EXISTS soft_daily_threshold_czk;

-- Refuses to finish if the database is left at neither shape, so a half-applied run cannot look
-- like a successful one. Nullability is checked and not only presence: a nullable ceiling loads as
-- a null Money and Customer's constructor refuses it, which would surface as a failure to read
-- the customer rather than as a missed migration step.
DO $$
DECLARE
    ceiling_nullability TEXT;
BEGIN
    SELECT is_nullable INTO ceiling_nullability
      FROM information_schema.columns
     WHERE table_schema = current_schema()
       AND table_name   = 'customers'
       AND column_name  = 'daily_limit_czk';

    IF ceiling_nullability IS NULL THEN
        RAISE EXCEPTION 'customers.daily_limit_czk was not created';
    END IF;
    IF ceiling_nullability <> 'NO' THEN
        RAISE EXCEPTION 'customers.daily_limit_czk is still nullable';
    END IF;

    IF NOT EXISTS (SELECT 1
                     FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name   = 'customers'
                      AND column_name  = 'soft_daily_threshold_czk') THEN
        RAISE EXCEPTION 'customers.soft_daily_threshold_czk was not created';
    END IF;

    IF EXISTS (SELECT 1
                 FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name   = 'accounts'
                  AND column_name IN ('daily_limit_czk', 'soft_daily_threshold_czk')) THEN
        RAISE EXCEPTION 'accounts still carries a limit column';
    END IF;
END $$;
