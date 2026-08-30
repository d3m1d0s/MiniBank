-- Drops the MiniBank schema. Recreate it by running db/init/schema.sql afterwards.
--
-- This is deliberately NOT in db/init/, which docker compose mounts as the container's
-- initialisation directory: every script there runs automatically on a fresh volume, and
-- a drop-everything script does not belong in that set. This one only ever runs when a
-- person names it.
--
--   docker compose exec -T db psql -U minibank -d minibank_test < db/reset.sql
--   docker compose exec -T db psql -U minibank -d minibank_test < db/init/schema.sql
--
-- Whatever database -d names is the database that loses its data, including the users
-- table, which CASCADE reaches through the foreign key on customers.

-- The journal goes before the alert it hangs off, which is the order the foreign key runs in.
-- CASCADE would reach it anyway; naming it here is what keeps this list readable as the inverse
-- of schema.sql rather than as a list that happens to work.
--
-- It was missing, and the way it failed is worth writing down. This script dropped six of the
-- seven tables schema.sql creates, so the documented pair below left fraud_alert_notes standing
-- and the recreate died on `relation "fraud_alert_notes" already exists`. Under ON_ERROR_STOP
-- that abandons the schema half-built, and without it the run continues and reports success over
-- a database missing whatever followed. The table arrived in a later migration than this file,
-- which is the whole of how it was missed: a table added anywhere but here is invisible until
-- somebody resets a database and reads the error.
DROP TABLE IF EXISTS fraud_alert_notes CASCADE;
DROP TABLE IF EXISTS fraud_alerts CASCADE;
DROP TABLE IF EXISTS transfers CASCADE;
DROP TABLE IF EXISTS beneficiaries CASCADE;
DROP TABLE IF EXISTS accounts CASCADE;
DROP TABLE IF EXISTS customers CASCADE;
DROP TABLE IF EXISTS users CASCADE;

DROP SEQUENCE IF EXISTS fraud_alert_notes_id_seq;
DROP SEQUENCE IF EXISTS fraud_alerts_id_seq;
DROP SEQUENCE IF EXISTS transfers_id_seq;
DROP SEQUENCE IF EXISTS beneficiaries_id_seq;
DROP SEQUENCE IF EXISTS accounts_id_seq;
DROP SEQUENCE IF EXISTS customers_id_seq;
DROP SEQUENCE IF EXISTS users_id_seq;
