package cz.vsb.minibank.uow;

import cz.vsb.minibank.application.MinibankProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one guard standing between {@code mvn test} and the application's own data, pinned.
 * <p>
 * {@link MinibankSqlUowTests} and {@link SqlSchemaPassTest} truncate every table before each
 * case, and {@code CASCADE} carries that through the foreign key on {@code users} into the demo
 * logins. Nothing about that is reversible, so the check that decides whether the two urls name
 * one database is worth its own tests - and it gets them here, with no database anywhere in
 * sight: the decision is taken from two strings, which is exactly what makes it testable at all.
 * <p>
 * Both halves of the check are asserted, because both can fail expensively. Passing a truncation
 * run through to the application database destroys it; refusing the documented setup, or failing
 * a fresh clone that has configured nothing, breaks the build for everyone who never had a
 * database to protect.
 */
class TestDatabaseGuardTest {

    private static final String APPLICATION_DEFAULT = MinibankProperties.SQL_URL_DEFAULT;

    private static final List<String> KEYS =
            List.of(TestDatabase.URL, MinibankProperties.SQL_URL);

    private final Map<String, String> saved = new HashMap<>();

    /** The suite is normally started with the test url on the command line; these tests set it. */
    @BeforeEach
    void isolateFromTheCommandLine() {
        for (String key : KEYS) {
            String value = System.getProperty(key);
            if (value != null) {
                saved.put(key, value);
            }
            System.clearProperty(key);
        }
    }

    @AfterEach
    void restoreTheCommandLine() {
        KEYS.forEach(System::clearProperty);
        saved.forEach(System::setProperty);
        saved.clear();
    }

    // -------------------------------------------------------------------------
    // The decision itself
    // -------------------------------------------------------------------------

    /**
     * Every spelling of the application database that a string comparison used to wave through.
     *
     * Each entry reaches the same database as {@code jdbc:postgresql://localhost:5432/minibank}
     * or is indistinguishable from it, and each was written the way an operator would arrive at
     * it: the loopback address instead of its name, an appended parameter, the host typed in
     * another case, the machine's real name, and the port the documented compose setup publishes
     * - which is the worst of them, because the application url this is compared against is
     * usually the compile-time default rather than the url the application really uses.
     */
    @Test
    void anotherSpellingOfTheApplicationDatabaseIsNotASeparateDatabase() {
        List<String> spellings = List.of(
                "jdbc:postgresql://localhost:5432/minibank",
                "jdbc:postgresql://127.0.0.1:5432/minibank",
                "jdbc:postgresql://localhost:5432/minibank?sslmode=disable",
                "jdbc:postgresql://localhost:5432/minibank?user=minibank&ApplicationName=tests",
                "jdbc:postgresql://LOCALHOST:5432/minibank",
                "jdbc:postgresql://localhost:5432/MINIBANK",
                "jdbc:postgresql://localhost/minibank",
                "jdbc:postgresql://db.internal.example:5432/minibank",
                "jdbc:postgresql://localhost:55432/minibank",
                "jdbc:postgresql:minibank");

        for (String spelling : spellings) {
            assertTrue(TestDatabase.mayBeTheSameDatabase(spelling, APPLICATION_DEFAULT),
                    spelling + " names the application database and would be truncated");
        }
    }

    /**
     * And the setups the documentation tells people to create are left alone.
     *
     * The second pair is the one the README describes end to end: both databases on the
     * container's published port, told apart by nothing but their names.
     */
    @Test
    void aDatabaseWithANameOfItsOwnIsAllowed() {
        assertFalse(TestDatabase.mayBeTheSameDatabase(TestDatabase.URL_DEFAULT, APPLICATION_DEFAULT),
                "a fresh clone configures nothing and must still be able to run");
        assertFalse(TestDatabase.mayBeTheSameDatabase(
                        "jdbc:postgresql://localhost:55432/minibank_test",
                        "jdbc:postgresql://localhost:55432/minibank"),
                "the documented compose setup keeps both databases on one server");
        assertFalse(TestDatabase.mayBeTheSameDatabase(
                        "jdbc:postgresql://localhost:55432/minibank_test", APPLICATION_DEFAULT),
                "and it is normally run with the application url left at its default");
        assertFalse(TestDatabase.mayBeTheSameDatabase(
                        "jdbc:postgresql://127.0.0.1:55432/minibank_test?sslmode=disable",
                        APPLICATION_DEFAULT),
                "a separate database stays separate however it is spelled");
    }

    /**
     * A url of a shape this cannot read is treated as the application database.
     *
     * Not being able to read a url is not evidence that it points somewhere else, and the answer
     * this guard gives decides whether a TRUNCATE runs, so the unreadable case has to fall on the
     * refusing side. The wrong url of the pair is covered too: the application key is just as
     * hand-written as the test one.
     */
    @Test
    void aUrlNobodyCanReadIsNotEvidenceOfASeparateDatabase() {
        List<String> unreadable = List.of(
                "jdbc:postgresql://localhost:5432",
                "jdbc:postgresql://localhost:5432/",
                "jdbc:postgresql:",
                "jdbc:mysql://localhost:3306/minibank_test",
                "minibank_test",
                "");

        for (String url : unreadable) {
            assertTrue(TestDatabase.mayBeTheSameDatabase(url, APPLICATION_DEFAULT),
                    "'" + url + "' names no database this can read, so it may be any of them");
            assertTrue(TestDatabase.mayBeTheSameDatabase(TestDatabase.URL_DEFAULT, url),
                    "and the application side of the comparison is no more trustworthy");
        }
    }

    // -------------------------------------------------------------------------
    // What the SQL test classes actually call
    // -------------------------------------------------------------------------

    /**
     * The wiring, through the two system properties the guard really reads.
     *
     * This is the accident in full: the operator points the tests at the application database by
     * its address rather than by its name, and nothing else about the run looks wrong.
     */
    @Test
    void theGuardStopsARunPointedAtTheApplicationDatabaseUnderAnotherName() {
        System.setProperty(TestDatabase.URL, "jdbc:postgresql://127.0.0.1:5432/minibank");
        System.setProperty(MinibankProperties.SQL_URL, "jdbc:postgresql://localhost:5432/minibank");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> TestDatabase.requireSeparateFromApplicationDatabase());
        assertTrue(refused.getMessage().contains("jdbc:postgresql://127.0.0.1:5432/minibank"),
                "the refusal must quote what the tests were pointed at: " + refused.getMessage());
        assertTrue(refused.getMessage().contains(TestDatabase.URL),
                "and name the key that steers them: " + refused.getMessage());
    }

    @Test
    void theDocumentedSetupIsLetThrough() {
        System.setProperty(TestDatabase.URL, "jdbc:postgresql://localhost:55432/minibank_test");
        System.setProperty(MinibankProperties.SQL_URL, "jdbc:postgresql://localhost:55432/minibank");

        assertDoesNotThrow(() -> TestDatabase.requireSeparateFromApplicationDatabase(),
                "the setup the README describes has to keep running");
    }

    /**
     * A clone with nothing configured must reach the reachability probe and be skipped there,
     * rather than being stopped here. The two defaults differ by name, which is the whole reason
     * they are two keys with two defaults.
     */
    @Test
    void aCloneThatHasConfiguredNothingIsLeftToSkipOnItsOwn() {
        assertEquals(TestDatabase.URL_DEFAULT, TestDatabase.url(),
                "the fixture must have cleared the command line, or this proves nothing");
        assertEquals(APPLICATION_DEFAULT, MinibankProperties.sqlUrl());

        assertDoesNotThrow(() -> TestDatabase.requireSeparateFromApplicationDatabase());
    }
}
