-- Second database, for the SQL integration tests.
--
-- They truncate every table before each case, so they must never be pointed at the
-- database the application uses. Creating it here means a clone needs no manual step:
-- docker compose up -d leaves both databases ready.
--
-- Like every script in this directory, this runs only on first initialisation of an
-- empty data volume.

CREATE DATABASE minibank_test OWNER minibank;

\connect minibank_test

\i /docker-entrypoint-initdb.d/schema.sql
