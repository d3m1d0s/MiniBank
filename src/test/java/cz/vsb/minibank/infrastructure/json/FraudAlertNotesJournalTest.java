package cz.vsb.minibank.infrastructure.json;

import cz.vsb.minibank.domain.fraud.FraudAlert;
import cz.vsb.minibank.domain.fraud.FraudAlertNote;
import cz.vsb.minibank.infrastructure.Bootstrap;
import cz.vsb.minibank.infrastructure.uow.UowScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The notes journal on the JSON backend: what a store written before it existed becomes, and what
 * appending to one does.
 *
 * THE MIGRATION IS TESTED HERE because on this backend it is Java rather than SQL. The other
 * backend carries its rows with db/migrate/fraud-alert-comment-and-notes-journal.sql, applied by
 * hand once; this one carries them as the document is read, so it runs on every open and has to be
 * right on every open.
 */
class FraudAlertNotesJournalTest {

    private static final Instant RAISED_AT = Instant.parse("2026-02-01T09:00:00Z");

    @TempDir
    Path tempDir;

    /**
     * A store written before the journal existed opens with its single notes text as the first
     * entry of the alert's journal.
     *
     * Nothing is thrown away, which is the whole requirement. The entry names no author, because
     * the column recorded none and a placeholder would name somebody who never wrote anything, and
     * it is dated at the alert's own creation instant, which is the earliest moment the note could
     * have been written and is what keeps it at the top.
     */
    @Test
    void theSingleNotesTextOfALegacyStoreBecomesTheFirstEntryOfItsJournal() throws Exception {
        Bootstrap infra = new Bootstrap(storeWithLegacyNotes("Called the payer, no answer"));

        List<FraudAlertNote> journal = infra.alerts.notesOf(9001);

        assertEquals(1, journal.size(), "the text a legacy store holds must not be lost");
        assertEquals("Called the payer, no answer", journal.get(0).text());
        assertNull(journal.get(0).author(),
                "the column recorded no author, and inventing one would put a name in a case file"
                        + " that names nobody");
        assertEquals(RAISED_AT, journal.get(0).writtenAt());
        assertEquals(9001, journal.get(0).alertId());
    }

    /**
     * The carried entry stays first when an analyst writes the next one, and neither replaces the
     * other.
     */
    @Test
    void anEntryAppendedAfterTheCarriedOneStandsBehindItAndKeepsIt() throws Exception {
        Bootstrap infra = new Bootstrap(storeWithLegacyNotes("Called the payer, no answer"));

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            infra.alerts.appendNote(new FraudAlertNote(
                    9001, "anna.analyst", RAISED_AT.plusSeconds(3600), "payer called back"));
            scope.uow().commit();
        }

        List<FraudAlertNote> journal = infra.alerts.notesOf(9001);

        assertEquals(2, journal.size());
        assertEquals("Called the payer, no answer", journal.get(0).text());
        assertEquals("payer called back", journal.get(1).text());
        assertEquals("anna.analyst", journal.get(1).author());
    }

    /**
     * Saving the alert does not take its journal with it.
     *
     * This is the case the shape was chosen for. Saving an alert on this backend REPLACES its
     * stored record with a fresh one built from the aggregate, and the aggregate does not carry
     * its journal; a journal that lived on the alert record would therefore be erased by an
     * assignment, which is an operation that has nothing to do with notes at all.
     */
    @Test
    void assigningAnAlertDoesNotEraseItsJournal() throws Exception {
        Bootstrap infra = new Bootstrap(storeWithLegacyNotes("Called the payer, no answer"));

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            infra.alerts.appendNote(new FraudAlertNote(
                    9001, "anna.analyst", RAISED_AT.plusSeconds(3600), "payer called back"));
            scope.uow().commit();
        }

        try (UowScope scope = new UowScope(infra.uowFactory.begin())) {
            FraudAlert alert = infra.alerts.byId(9001).orElseThrow();
            alert.assignTo("bob.analyst");
            infra.alerts.save(alert);
            scope.uow().commit();
        }

        assertEquals(2, infra.alerts.notesOf(9001).size(),
                "an assignment must not be able to destroy what analysts wrote");
    }

    /**
     * The journal survives being written to the file and read back by a second process.
     *
     * A round trip rather than one instance's memory: the carry happens as the document is read,
     * so a store that has been saved since must come back with the journal it acquired and not
     * with the legacy text again, which would double every carried entry on every open.
     */
    @Test
    void theJournalRoundTripsThroughTheFileAndTheCarriedEntryIsNotDoubled() throws Exception {
        String path = storeWithLegacyNotes("Called the payer, no answer");

        Bootstrap first = new Bootstrap(path);
        try (UowScope scope = new UowScope(first.uowFactory.begin())) {
            first.alerts.appendNote(new FraudAlertNote(
                    9001, "anna.analyst", RAISED_AT.plusSeconds(3600), "payer called back"));
            scope.uow().commit();
        }
        first.storeGuard.close();

        Bootstrap second = new Bootstrap(path);
        List<FraudAlertNote> journal = second.alerts.notesOf(9001);

        assertEquals(2, journal.size(),
                "reopening the store must neither lose the journal nor carry the legacy text a"
                        + " second time");
        assertEquals("Called the payer, no answer", journal.get(0).text());
        assertEquals("payer called back", journal.get(1).text());
        second.storeGuard.close();
    }

    /** An alert nobody has written on has an empty journal, and so does an id no alert has. */
    @Test
    void anAlertWithNoNotesAndAnAlertThatDoesNotExistBothAnswerWithAnEmptyJournal()
            throws Exception {
        Bootstrap infra = new Bootstrap(storeWithLegacyNotes(null));

        assertTrue(infra.alerts.notesOf(9001).isEmpty());
        assertTrue(infra.alerts.notesOf(4242).isEmpty(),
                "a missing alert is the detail route's question, not this one's");
    }

    /**
     * Writes a store in the shape this project wrote before the journal existed: one alert, with
     * its notes in the single field the alert record used to carry.
     *
     * Written as text rather than through the mapper on purpose. The mapper no longer produces
     * that field, so a fixture built with it could not express the very thing being migrated.
     *
     * @param notes the legacy text, or null for an alert nobody had written on
     */
    private String storeWithLegacyNotes(String notes) throws Exception {
        String notesField = (notes == null) ? "" : "\"notes\": \"" + notes + "\", ";

        String document = "{"
                + "\"customers\": [], \"accounts\": [], \"transfers\": [],"
                + "\"fraudAlerts\": [{"
                + "\"id\": 9001, \"transferId\": 5001, \"state\": \"NEW\","
                + "\"reason\": \"New beneficiary + high amount\","
                + "\"createdAt\": \"" + RAISED_AT + "\", \"tags\": [], "
                + notesField
                + "\"riskScore\": 80"
                + "}],"
                + "\"sequences\": {\"customer\": 1, \"account\": 100, \"beneficiary\": 10,"
                + " \"transfer\": 5002, \"fraudAlert\": 9002}"
                + "}";

        Path file = tempDir.resolve("data.json");
        Files.writeString(file, document);
        return file.toString();
    }
}
