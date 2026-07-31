package cz.vsb.minibank.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests how Bootstrap reacts to the state of the JSON data store file:
 * an absent or empty file starts a new store, an unreadable one fails loudly.
 */
class BootstrapTest {

    @TempDir
    Path tempDir;

    @Test
    void anAbsentFileStartsAnEmptyStore() {
        Path missing = tempDir.resolve("does-not-exist.json");

        Bootstrap infra = new Bootstrap(missing.toString());

        assertTrue(infra.store.data().customers.isEmpty());
        assertTrue(infra.store.data().accounts.isEmpty());
        assertTrue(infra.customers.byId(1).isEmpty());
    }

    @Test
    void anEmptyFileStartsAnEmptyStore() throws IOException {
        Path empty = tempDir.resolve("empty.json");
        Files.writeString(empty, "");

        Bootstrap infra = new Bootstrap(empty.toString());

        assertTrue(infra.store.data().customers.isEmpty());
    }

    @Test
    void aValidFileIsLoaded() throws IOException {
        Path store = tempDir.resolve("valid.json");
        Files.writeString(store, """
                {
                  "customers" : [ {
                    "id" : 2,
                    "name" : "Demo",
                    "email" : "demo@example.com",
                    "address" : { "street" : "Hlavni 1", "city" : "Ostrava" },
                    "accountIds" : [ ],
                    "beneficiaries" : [ ]
                  } ],
                  "accounts" : [ ],
                  "transfers" : [ ],
                  "fraudAlerts" : [ ],
                  "sequences" : { "customer" : 3, "account" : 100, "beneficiary" : 10,
                                  "transfer" : 5001, "fraudAlert" : 9001 }
                }
                """);

        Bootstrap infra = new Bootstrap(store.toString());

        assertTrue(infra.customers.byId(2).isPresent());
        assertEquals("Demo", infra.customers.byId(2).get().name());
    }

    @Test
    void malformedJsonFailsLoudlyAndNamesThePath() throws IOException {
        Path broken = tempDir.resolve("broken.json");
        Files.writeString(broken, "{ this is not json");

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> new Bootstrap(broken.toString()));

        assertTrue(ex.getMessage().contains(broken.toString()),
                "The failure must name the offending file: " + ex.getMessage());
        assertNotNull(ex.getCause(), "The original parse error must be preserved");
    }

    @Test
    void anUnknownFieldFailsLoudlyInsteadOfSilentlyDiscardingTheStore() throws IOException {
        Path store = tempDir.resolve("unknown-field.json");
        Files.writeString(store, """
                {
                  "customers" : [ ],
                  "accounts" : [ ],
                  "transfers" : [ ],
                  "fraudAlerts" : [ ],
                  "sequences" : { "customer" : 1, "account" : 100, "beneficiary" : 10,
                                  "transfer" : 5001, "fraudAlert" : 9001 },
                  "somethingFromAnotherVersion" : 42
                }
                """);

        assertThrows(IllegalStateException.class, () -> new Bootstrap(store.toString()));
    }

    @Test
    void anUnreadableStoreIsNotOverwritten() throws IOException {
        Path broken = tempDir.resolve("keep-me.json");
        String original = "{ this is not json";
        Files.writeString(broken, original);

        assertThrows(IllegalStateException.class, () -> new Bootstrap(broken.toString()));

        assertEquals(original, Files.readString(broken),
                "Refusing to start must leave the file untouched");
    }
}
