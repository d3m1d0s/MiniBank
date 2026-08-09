-- One fraud alert per transfer, enforced by the database.
--
-- Run by hand against each database, like every script in this directory:
--   psql -U minibank -d minibank      -f db/migrate/one-alert-per-transfer.sql
--   psql -U minibank -d minibank_test -f db/migrate/one-alert-per-transfer.sql
--
-- Mirrored into db/init/schema.sql, which is what a fresh volume gets.
--
-- Why the constraint is worth having even though nothing writes a second alert today.
-- There are two creation sites, not one: TransferApplicationService creates an alert when a
-- payment is first held, and again when the rule is re-asked at authorization. The second is
-- guarded only by a read - "is there an alert for this transfer already?" - and that read and
-- the insert that follows it are not atomic. Two authorizations racing on one transfer can both
-- see no alert and both insert. The comment at that site says what a second alert costs: an
-- approved payment becomes permanently unconfirmable, because releasing it looks for one alert
-- and finds two.
--
-- So this turns a silent, permanent data corruption into a loud failure on the losing thread.
-- That is the trade, and it is deliberate: SqlFraudAlertRepository reads alerts back with
-- ORDER BY id ASC LIMIT 1, a workaround that exists precisely because the shape it guards
-- against was representable. It stops being representable here.
--
-- The plain index goes with it: a UNIQUE constraint creates its own index on the same column,
-- so keeping idx_fraud_alerts_transfer would mean maintaining two indexes on one column.

\set ON_ERROR_STOP on

DO $$
BEGIN
    ALTER TABLE fraud_alerts
        ADD CONSTRAINT fraud_alerts_one_per_transfer UNIQUE (transfer_id);
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

DROP INDEX IF EXISTS idx_fraud_alerts_transfer;

-- Refuses to finish if the constraint is not there, so a half-applied run cannot look like a
-- successful one.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fraud_alerts_one_per_transfer') THEN
        RAISE EXCEPTION 'fraud_alerts_one_per_transfer was not created';
    END IF;
END $$;
