-- The analyst's comment becomes a column of its own, and the single notes blob becomes a journal.
-- Run once against any database created before this change.
--
-- MUST BE RUN AGAINST BOTH DATABASES. db/init/ runs only on an empty volume, and
-- db/init/test-database.sql applies schema.sql to minibank_test as well as minibank, so an
-- existing volume has two databases at the old shape and both need this:
--
--   Git Bash / sh:
--     docker compose exec -T db psql -U minibank -d minibank      < db/migrate/fraud-alert-comment-and-notes-journal.sql
--     docker compose exec -T db psql -U minibank -d minibank_test < db/migrate/fraud-alert-comment-and-notes-journal.sql
--
--   PowerShell, where "<" is a reserved operator and the line above is a parse error:
--     Get-Content db/migrate/fraud-alert-comment-and-notes-journal.sql | docker compose exec -T db psql -U minibank -d minibank
--     Get-Content db/migrate/fraud-alert-comment-and-notes-journal.sql | docker compose exec -T db psql -U minibank -d minibank_test
--
-- Skipping minibank_test leaves the SQL integration tests failing on a missing table while the
-- application runs, which reads as a code bug rather than a missed step.
--
-- Deliberately NOT in db/init/, which docker compose runs unattended on a fresh volume: that
-- directory must describe the schema, not patch it. Mirrored into db/init/schema.sql, which is
-- what a fresh volume gets.
--
-- WHAT THIS CHANGES, AND WHY.
--
-- 1. fraud_alerts.decision_comment. The analyst's own comment was appended into reason behind a
--    " | ". reason is the sentence the rules produced to say why the alert was raised; the comment
--    is what a person concluded after looking at the payment. Two facts, two authors, one line, and
--    no reader - screen or query - able to tell them apart.
--
-- 2. fraud_alert_notes. notes was one TEXT column that every save replaced. Two analysts working
--    the same alert overwrote each other silently, and nothing recorded who wrote what or when.
--
-- NOTHING IS THROWN AWAY, and both carry-overs happen here rather than being left for somebody to
-- do by hand.
--
-- The reason is SPLIT AT ITS FIRST " | ": the head goes back to being the risk reason and the tail
-- becomes the comment. That separator was written by exactly one line of code and the rules' own
-- sentences are two fixed strings that contain no bar, so the split is exact rather than a guess.
-- A row with no bar in it was never appended to and is left alone.
--
-- The notes text becomes the FIRST ENTRY of that alert's journal, with no author, because the
-- column recorded none and a placeholder would name somebody who never wrote anything, and dated
-- at the alert's own creation instant, because that is the earliest moment the note could have
-- been written and it is what keeps the carried entry at the top of the journal.
--
-- Then the notes column is dropped. That is what makes step 4 idempotent - a second run finds no
-- column and skips - and it removes the only remaining way for an upsert to overwrite a note.

-- Without this psql reports success after a post-condition below fails, and an operator chaining
-- on the exit code is told a half-migrated database is migrated.
\set ON_ERROR_STOP on

-- 1. The comment's own column. Metadata-only on PostgreSQL 11 and later, and this project runs
--    postgres:14, so it does not rewrite the table however many alerts exist.
ALTER TABLE fraud_alerts ADD COLUMN IF NOT EXISTS decision_comment TEXT;

-- 2. Pull the appended comment back out of the reason it was appended to. Only rows that were
--    appended to are touched, and only when the comment column is still empty, so a second run
--    over an already-split database changes nothing.
UPDATE fraud_alerts
   SET decision_comment = substring(reason from position(' | ' in reason) + 3),
       reason           = substring(reason from 1 for position(' | ' in reason) - 1)
 WHERE decision_comment IS NULL
   AND position(' | ' in reason) > 0;

-- 3. The journal.
CREATE TABLE IF NOT EXISTS fraud_alert_notes (
    id         INTEGER PRIMARY KEY,
    alert_id   INTEGER NOT NULL REFERENCES fraud_alerts(id) ON DELETE CASCADE,
    author     VARCHAR(100),
    written_at TIMESTAMPTZ NOT NULL,
    text       TEXT NOT NULL CHECK (btrim(text) <> '')
);

CREATE SEQUENCE IF NOT EXISTS fraud_alert_notes_id_seq;
ALTER TABLE fraud_alert_notes
    ALTER COLUMN id SET DEFAULT nextval('fraud_alert_notes_id_seq');
ALTER SEQUENCE fraud_alert_notes_id_seq OWNED BY fraud_alert_notes.id;

CREATE INDEX IF NOT EXISTS fraud_alert_notes_by_alert
    ON fraud_alert_notes (alert_id, written_at, id);

-- 4. Carry every stored note into the journal, then drop the column it came from.
--
--    Guarded on the column still existing rather than on the table being empty: an operator who
--    re-runs this after analysts have already added notes must not have their journal refused, and
--    must not get a second copy of the carried entry either. Once the column is gone this block is
--    a no-op forever.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_name = 'fraud_alerts'
           AND column_name = 'notes'
    ) THEN
        INSERT INTO fraud_alert_notes (alert_id, author, written_at, text)
        SELECT id, NULL, created_at, notes
          FROM fraud_alerts
         WHERE notes IS NOT NULL
           AND btrim(notes) <> '';

        ALTER TABLE fraud_alerts DROP COLUMN notes;
    END IF;
END $$;

-- Refuses to finish if any of the three shapes is not there, so a half-applied run cannot look
-- like a successful one.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_name = 'fraud_alerts'
           AND column_name = 'decision_comment'
    ) THEN
        RAISE EXCEPTION 'fraud_alerts.decision_comment was not created';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_name = 'fraud_alerts'
           AND column_name = 'notes'
    ) THEN
        RAISE EXCEPTION 'fraud_alerts.notes was not dropped, so its contents may not have been carried';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM information_schema.tables
         WHERE table_name = 'fraud_alert_notes'
    ) THEN
        RAISE EXCEPTION 'fraud_alert_notes was not created';
    END IF;
END $$;
