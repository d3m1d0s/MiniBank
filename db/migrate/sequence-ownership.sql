-- Give each sequence to the column it numbers.
--
-- Run by hand against each database:
--   psql -U minibank -d minibank      -f db/migrate/sequence-ownership.sql
--   psql -U minibank -d minibank_test -f db/migrate/sequence-ownership.sql
--
-- Mirrored into db/init/schema.sql. Safe to run twice; ownership is a property, not a change.
--
-- All six sequences were created free-standing and then wired in with SET DEFAULT nextval(...),
-- which makes them work and leaves them owned by nobody. Two things follow, and only one of
-- them is visible:
--
--   TRUNCATE ... RESTART IDENTITY only restarts sequences owned by a column of the truncated
--   table, so on an unowned sequence it does nothing at all. Both SQL test suites ask for it in
--   their reset, and it has silently done nothing every time: ids climb across runs, which is
--   why a test transfer has been seen numbered in the thousands. After this they restart.
--
--   DROP TABLE leaves an unowned sequence behind. db/reset.sql drops all six by name for that
--   reason; those lines become redundant here rather than wrong, and are left alone.
--
-- OWNED BY rather than GENERATED AS IDENTITY, and the reason is not style. Every repository
-- allocates ids with SELECT nextval('<table>_id_seq') by name, and every insert supplies the id
-- it got. Identity columns would have to be BY DEFAULT to allow that, and the nextval calls
-- would keep working only because PostgreSQL happens to name an identity column's sequence
-- <table>_<column>_seq. That is an implementation detail to lean a codebase on. OWNED BY changes
-- no name, no default and no insert, and needs no Java at all.

\set ON_ERROR_STOP on

ALTER SEQUENCE customers_id_seq     OWNED BY customers.id;
ALTER SEQUENCE users_id_seq         OWNED BY users.id;
ALTER SEQUENCE accounts_id_seq      OWNED BY accounts.id;
ALTER SEQUENCE beneficiaries_id_seq OWNED BY beneficiaries.id;
ALTER SEQUENCE transfers_id_seq     OWNED BY transfers.id;
ALTER SEQUENCE fraud_alerts_id_seq  OWNED BY fraud_alerts.id;

DO $$
DECLARE unowned int;
BEGIN
    SELECT count(*) INTO unowned
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE c.relkind = 'S'
       AND n.nspname = 'public'
       AND NOT EXISTS (SELECT 1 FROM pg_depend d
                        WHERE d.objid = c.oid AND d.deptype = 'a');
    IF unowned > 0 THEN
        RAISE EXCEPTION '% sequence(s) still owned by nobody', unowned;
    END IF;
END $$;
