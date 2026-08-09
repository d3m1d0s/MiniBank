-- Hold every transfer that has an open alert. Run once against any database seeded before the
-- fraud review gate existed.
--
-- Deliberately NOT in db/init/, which docker compose runs automatically on a fresh volume:
-- a fresh database has no rows to move. Same reasoning as db/reset.sql - this one only ever
-- runs when a person names it.
--
--   docker compose exec -T db psql -U minibank -d minibank < db/migrate/hold-alerted-transfers.sql
--
-- Before this change an alerted transfer was stored WAITING_AUTH, so the customer could confirm
-- it while its alert sat NEW in the analyst's queue. The gate is a write-time invariant on the
-- transfer's own status, not a read of the alert, so those rows do not acquire the hold by
-- being loaded: they stay confirmable until they are moved.
--
-- auth_valid_until is cleared with the status. A held transfer has no authorization window, and
-- leaving a stale deadline behind would make the transfer expire the moment an analyst released
-- it - the customer would lose a payment to a clock that had been running while they waited.
--
-- A WAITING_AUTH transfer whose alert is already OK or SUSPICIOUS is left alone. Its alert has
-- been decided, and a decided alert is exactly the state in which the customer may confirm.
-- The `a.state = 'NEW'` predicate is what says so.
--
-- No schema change is needed anywhere: transfers.status is VARCHAR(32) with no enum type, no
-- CHECK and no lookup FK, and 'HELD_FOR_REVIEW' is fifteen characters. Idempotent.

UPDATE transfers t
   SET status = 'HELD_FOR_REVIEW',
       auth_valid_until = NULL
 WHERE t.status = 'WAITING_AUTH'
   AND EXISTS (SELECT 1
                 FROM fraud_alerts a
                WHERE a.transfer_id = t.id
                  AND a.state = 'NEW');
