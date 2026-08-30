package cz.vsb.minibank.infrastructure.json;

import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.domain.value.Money;
import cz.vsb.minibank.infrastructure.Bootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which stored rows the JSON lookup by IBAN claims, and which it leaves to nobody.
 *
 * The question is not academic bookkeeping. TransferApplicationService.settle asks it once and
 * routes on the answer: an IBAN this bank holds is credited here, and one it does not is handed
 * to the external payment gateway. So a row that one backend claims and the other does not is a
 * dataset that sends the same money to two different places depending on which adapter reads it.
 *
 * SqlAccountRepository binds the already-normalized {@code iban.value()} to {@code WHERE iban = ?}
 * and there is no constraint normalizing that column, so a row stored in lower case belongs to no
 * account there. This scan folded case, so the same row was an account of this bank on the JSON
 * side. Every row either backend writes is normalized - the application only ever writes
 * {@code Account.iban().value()} - which is why the divergence needs a hand-edited file to show
 * itself, and the JSON store is a file people open and edit.
 */
class JsonAccountByIbanTest {

    @TempDir
    Path tempDir;

    private static final int ACCOUNT_ID = 100;

    /** The comparable form: what the application writes and what a stored row is expected to hold. */
    private static final String NORMALIZED = "CZ6508000000192000145399";

    /** The same IBAN as a customer types it, which is what the query side has to tolerate. */
    private static final String AS_TYPED = "cz65 0800 0000 1920 0014 5399";

    /**
     * The same characters with only the case changed, which is exactly what the old
     * {@code equalsIgnoreCase} matched and a spaced spelling never did.
     */
    private static final String STORED_IN_LOWER_CASE = "cz6508000000192000145399";

    /**
     * The half that must not change. The query value has been through IBAN's constructor before it
     * reaches the repository, so the caller's spelling is already gone by then and an exact
     * comparison against a normalized row still matches.
     */
    @Test
    void anAccountWrittenByTheApplicationIsFoundHoweverTheQueryIsSpelled() {
        Bootstrap writer = new Bootstrap(storePath().toString());
        writer.accounts.save(new Account(ACCOUNT_ID, new IBAN(NORMALIZED), Money.czk(5_000)));

        // A second Bootstrap over the same file, so this reads the row that reached the disk
        // rather than anything the writing instance still holds.
        Bootstrap reader = new Bootstrap(storePath().toString());

        assertEquals(ACCOUNT_ID, reader.accounts.byIban(new IBAN(NORMALIZED)).orElseThrow().id());
        assertEquals(ACCOUNT_ID, reader.accounts.byIban(new IBAN(AS_TYPED)).orElseThrow().id(),
                "the query is normalized by the IBAN type, so its spelling cannot decide the answer");
        assertEquals(ACCOUNT_ID, reader.accounts.inBankByIban(AS_TYPED).orElseThrow().id(),
                "and the routing question, which takes a raw snapshot, answers the same");
    }

    /**
     * The half that changed. This row is a hand edit - nothing in the application can write it -
     * and SQL has never found it, so finding it here was the two backends disagreeing about where
     * a payment goes.
     */
    @Test
    void aRowStoredInLowerCaseIsClaimedByNeitherBackend() throws IOException {
        writeStoreHolding("\"iban\": \"" + STORED_IN_LOWER_CASE + "\", ");

        Bootstrap infra = new Bootstrap(storePath().toString());

        assertTrue(infra.accounts.byId(ACCOUNT_ID).isPresent(),
                "the row is readable by id; it is the lookup by IBAN that must not claim it");
        assertTrue(infra.accounts.byIban(new IBAN(NORMALIZED)).isEmpty(),
                "a denormalized row is invisible to the SQL predicate and must be invisible here");
        assertTrue(infra.accounts.inBankByIban(NORMALIZED).isEmpty(),
                "so the payment leaves the bank on both backends rather than on one of them");
    }

    /**
     * A row with the key deleted rather than mistyped. It belongs to no account, which is what the
     * SQL predicate answers for a NULL column; the comparison is ordered so that it answers that
     * instead of throwing.
     */
    @Test
    void aRowWithNoStoredIbanBelongsToNoAccountRatherThanFailing() throws IOException {
        writeStoreHolding("");

        Bootstrap infra = new Bootstrap(storePath().toString());

        assertTrue(infra.accounts.byIban(new IBAN(NORMALIZED)).isEmpty());
    }

    /**
     * One account row carrying whatever iban fragment the caller supplies, and otherwise exactly
     * what the application itself writes, so each test differs from a loadable row in the one
     * field it is about.
     */
    private void writeStoreHolding(String ibanField) throws IOException {
        Files.writeString(storePath(), """
                {
                  "customers": [], "transfers": [], "fraudAlerts": [],
                  "accounts": [
                    { "id": %d, %s"balance": 5000.00, "dailyLimit": 10000.00 }
                  ]
                }
                """.formatted(ACCOUNT_ID, ibanField));
    }

    private Path storePath() {
        return tempDir.resolve("data.json");
    }
}
