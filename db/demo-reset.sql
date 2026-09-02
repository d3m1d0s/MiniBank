-- Empties the MiniBank tables so the next start of the API seeds the demo dataset again.
-- The schema itself is untouched: this is the difference between this script and db/reset.sql,
-- which drops the tables and needs db/init/schema.sql run after it.
--
--   scripts/demo-reset.sh          Git Bash / sh
--   scripts/demo-reset.ps1         PowerShell
--
-- or by hand:
--
--   docker compose exec -T db psql -U minibank -d minibank < db/demo-reset.sql
--
-- Then restart the API. Seeding runs once at startup, under the demo profile, so an empty
-- database on its own shows nothing until the process comes back.
--
-- Why emptying is what it takes. DemoScenario.seed() decides it has already run by looking for
-- one account, PRIMARY_IBAN, and returns early when it finds it. It therefore cannot notice a
-- fixture that has drifted, been paid down, or been left mid-review by somebody trying the
-- screens, and there was no way to get the dataset back short of docker compose down -v, which
-- discards the volume and both databases with it.
--
-- users goes with the rest and not because the demo data lives in it. DemoUsersInitializer
-- creates the alice and fraud logins only when they are missing, and binds alice to the customer
-- id seed() just returned. customers.id is referenced by users.customer_id ON DELETE SET NULL, so
-- a run that kept the users would leave alice pointing at nothing, and, being present, she would
-- never be rebound to the new customer. The logins are throwaway values recreated on the same
-- start that seeds the data, so there is nothing here to preserve.
--
-- Whatever database -d names is the database that is emptied. Naming minibank_test does no harm
-- and no good: the SQL tests truncate what they need themselves.

\set ON_ERROR_STOP on

-- One statement, so the tables come back consistent or not at all, and so the foreign keys
-- between them never have to be satisfied half way through. CASCADE reaches nothing beyond the
-- seven, all of which are named anyway, for the same reason db/reset.sql names them: this list
-- has to stay readable as the inverse of schema.sql rather than as a list that happens to work.
-- RESTART IDENTITY puts the sequences back to their starting value; each is OWNED BY the column
-- it numbers, which is what brings it into the scope of this statement.
TRUNCATE TABLE
    fraud_alert_notes,
    fraud_alerts,
    transfers,
    beneficiaries,
    accounts,
    customers,
    users
RESTART IDENTITY CASCADE;

DO $$
DECLARE
    left_over BIGINT;
BEGIN
    SELECT (SELECT count(*) FROM customers)
         + (SELECT count(*) FROM accounts)
         + (SELECT count(*) FROM transfers)
         + (SELECT count(*) FROM beneficiaries)
         + (SELECT count(*) FROM fraud_alerts)
         + (SELECT count(*) FROM fraud_alert_notes)
         + (SELECT count(*) FROM users)
      INTO left_over;
    IF left_over > 0 THEN
        RAISE EXCEPTION '% row(s) survived the reset', left_over;
    END IF;
    RAISE NOTICE 'MiniBank tables are empty. Restart the API to seed the demo dataset again.';
END $$;
