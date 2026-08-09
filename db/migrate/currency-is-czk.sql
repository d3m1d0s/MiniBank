-- One currency, enforced by the database.
--
-- Run by hand against each database, like every script in this directory:
--   psql -U minibank -d minibank      -f db/migrate/currency-is-czk.sql
--   psql -U minibank -d minibank_test -f db/migrate/currency-is-czk.sql
--
-- Mirrored into db/init/schema.sql, which is what a fresh volume gets.
--
-- The bank keeps crowns and nothing else. Until now that was said in Java and in the column
-- names - balance_czk, daily_limit_czk, soft_daily_threshold_czk - and this one column, which
-- takes any three characters, was the hole in it. Both creation paths stamp 'CZK' and both
-- loaders now rebuild the amount from what is stored here, so the only way a foreign row ever
-- existed was somebody writing one by psql. That is exactly what this closes.
--
-- What such a row does today, which is why it is worth closing rather than tolerating: the SQL
-- loader rebuilds the amount faithfully while the stored fee is read back as crowns, so
-- amount.plus(fee) raises a bare "Currency mismatch" and the customer's payment screen answers
-- 500. The JSON adapter used to force the amount to crowns instead, so the same row spent real
-- money. Transfer's constructor now refuses it on both, and this makes the row unwritable.
--
-- Deliberately not NOT VALID. A CHECK added this way validates the rows already in the table, so
-- an existing foreign row fails the migration loudly instead of surviving under a constraint
-- that claims it cannot exist. db/migrate/a14-a6-schema-pass.sql refuses NOT VALID for the same
-- reason and says so. If this fails, the row it names is the finding, not the obstacle.

\set ON_ERROR_STOP on

DO $$
BEGIN
    ALTER TABLE transfers
        ADD CONSTRAINT transfers_currency_czk CHECK (currency = 'CZK');
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

-- Refuses to finish if the constraint is not there, so a half-applied run cannot look like a
-- successful one.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'transfers_currency_czk') THEN
        RAISE EXCEPTION 'transfers_currency_czk was not created';
    END IF;
END $$;
