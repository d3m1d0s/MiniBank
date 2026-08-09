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

DROP TABLE IF EXISTS fraud_alerts CASCADE;
DROP TABLE IF EXISTS transfers CASCADE;
DROP TABLE IF EXISTS beneficiaries CASCADE;
DROP TABLE IF EXISTS accounts CASCADE;
DROP TABLE IF EXISTS customers CASCADE;
DROP TABLE IF EXISTS users CASCADE;

DROP SEQUENCE IF EXISTS fraud_alerts_id_seq;
DROP SEQUENCE IF EXISTS transfers_id_seq;
DROP SEQUENCE IF EXISTS beneficiaries_id_seq;
DROP SEQUENCE IF EXISTS accounts_id_seq;
DROP SEQUENCE IF EXISTS customers_id_seq;
DROP SEQUENCE IF EXISTS users_id_seq;
