package cz.vsb.minibank.domain.fraud;

import cz.vsb.minibank.domain.exceptions.DataIntegrityException;

import java.time.Instant;
import java.util.Objects;

/**
 * One entry in an alert's notes journal: which alert, who wrote it, when, and what they wrote.
 *
 * WHY THIS IS A LIST AND NOT A FIELD. The alert used to carry its notes as a single string that
 * every save replaced. Two analysts working the same alert overwrote each other without either of
 * them being told, and the column recorded neither who had written what nor when. A case file that
 * loses the previous entry the moment a second person adds one is worse than no case file: the
 * queue is shared work by construction.
 *
 * APPENDING IS THE WHOLE CONTRACT. There is no edit and no delete, on either backend and at any
 * layer above them - {@code FraudAlertRepository.appendNote} is the only writer and it only ever
 * inserts. That is why nothing here carries a "last edited" instant: there is no such moment.
 *
 * @param alertId   the alert this entry belongs to
 * @param author    the analyst who wrote it, or null for the entry the migration carried over from
 *                  the single notes column, which recorded no author. Null is the honest answer
 *                  there; a placeholder would put a name in a case file that names nobody
 * @param writtenAt when it was written. For a carried entry this is the alert's own creation
 *                  instant, which is the earliest moment the note could have been written and is
 *                  what keeps it first in the journal
 * @param text      what the analyst wrote, never null and never blank. A journal entry that says
 *                  nothing is not a fact about the case, and both write paths drop a blank rather
 *                  than storing one
 */
public record FraudAlertNote(int alertId, String author, Instant writtenAt, String text) {

    public FraudAlertNote {
        Objects.requireNonNull(writtenAt, "writtenAt");
        if (text == null || text.isBlank()) {
            throw new DataIntegrityException(
                    "A note on fraud alert " + alertId + " carries no text");
        }
    }
}
