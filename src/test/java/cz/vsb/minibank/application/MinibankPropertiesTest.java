package cz.vsb.minibank.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The configuration keys are the contract between the REST API, the console entry points
 * and the test harness, so the defaults and the renamed keys are pinned here.
 */
class MinibankPropertiesTest {

    private static final String LEGACY_SQL_URL = "minibank.jdbcUrl";
    private static final String LEGACY_SQL_USER = "minibank.dbUser";
    private static final String LEGACY_SQL_PASSWORD = "minibank.dbPass";

    private static final List<String> KEYS = List.of(
            MinibankProperties.STORAGE,
            MinibankProperties.JSON_PATH,
            MinibankProperties.DEMO_PATH,
            MinibankProperties.SQL_URL,
            MinibankProperties.SQL_USER,
            MinibankProperties.SQL_PASSWORD,
            MinibankProperties.DEMO_RESET,
            MinibankProperties.DEMO_ENABLED,
            LEGACY_SQL_URL,
            LEGACY_SQL_USER,
            LEGACY_SQL_PASSWORD);

    private final Map<String, String> saved = new HashMap<>();

    /**
     * The suite is normally started with the database keys on the command line, so the
     * tests below would read those instead of the defaults they mean to assert.
     */
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

    @Test
    void defaultsAreUsedWhenNothingIsSet() {
        assertEquals("json", MinibankProperties.storage());
        assertEquals("storage/data.json", MinibankProperties.jsonPath());
        assertEquals("storage/demo.json", MinibankProperties.demoPath());
        assertEquals("jdbc:postgresql://localhost:5432/minibank", MinibankProperties.sqlUrl());
        assertEquals("minibank", MinibankProperties.sqlUser());
        assertEquals("minibank", MinibankProperties.sqlPassword());
        assertFalse(MinibankProperties.demoReset());
        assertTrue(MinibankProperties.demoEnabled());
    }

    @Test
    void everyKeyCanBeOverridden() {
        System.setProperty(MinibankProperties.STORAGE, "sql");
        System.setProperty(MinibankProperties.JSON_PATH, "/tmp/store.json");
        System.setProperty(MinibankProperties.DEMO_PATH, "/tmp/demo.json");
        System.setProperty(MinibankProperties.SQL_URL, "jdbc:postgresql://localhost:55432/minibank");
        System.setProperty(MinibankProperties.SQL_USER, "someone");
        System.setProperty(MinibankProperties.SQL_PASSWORD, "secret");
        System.setProperty(MinibankProperties.DEMO_RESET, "true");
        System.setProperty(MinibankProperties.DEMO_ENABLED, "false");

        assertEquals("sql", MinibankProperties.storage());
        assertEquals("/tmp/store.json", MinibankProperties.jsonPath());
        assertEquals("/tmp/demo.json", MinibankProperties.demoPath());
        assertEquals("jdbc:postgresql://localhost:55432/minibank", MinibankProperties.sqlUrl());
        assertEquals("someone", MinibankProperties.sqlUser());
        assertEquals("secret", MinibankProperties.sqlPassword());
        assertTrue(MinibankProperties.demoReset());
        assertFalse(MinibankProperties.demoEnabled());
    }

    /**
     * The demo switch is the one key whose default is true, so the direction matters: an absent
     * key has to leave the one-command demo exactly as it was, and only the literal false may
     * withhold it.
     */
    @Test
    void theDemoRunsUnlessItIsExplicitlySwitchedOff() {
        assertTrue(MinibankProperties.demoEnabled());

        System.setProperty(MinibankProperties.DEMO_ENABLED, "false");
        assertFalse(MinibankProperties.demoEnabled());

        System.setProperty(MinibankProperties.DEMO_ENABLED, "true");
        assertTrue(MinibankProperties.demoEnabled());
    }

    /**
     * Both demo switches are read the same way, and the shared convention is worth pinning on
     * the enabled key because its default runs the other direction: a typo there withholds the
     * demo rather than leaving it alone, so somebody reading this has to be able to see that
     * only the literal false was needed.
     */
    @Test
    void aDemoSwitchThatIsNeitherTrueNorFalseCountsAsFalse() {
        System.setProperty(MinibankProperties.DEMO_ENABLED, "yes");
        System.setProperty(MinibankProperties.DEMO_RESET, "yes");

        assertFalse(MinibankProperties.demoEnabled());
        assertFalse(MinibankProperties.demoReset());
    }

    /**
     * The demo runner used to share minibank.json.path with the console app while defaulting
     * it elsewhere, so one override silently moved both stores.
     */
    @Test
    void theDemoStoreIsSteeredIndependentlyOfTheJsonStore() {
        System.setProperty(MinibankProperties.JSON_PATH, "/tmp/store.json");

        assertEquals("/tmp/store.json", MinibankProperties.jsonPath());
        assertEquals("storage/demo.json", MinibankProperties.demoPath());
    }

    @Test
    void theRenamedDatabaseKeysAreRefusedRatherThanIgnored() {
        System.setProperty(LEGACY_SQL_URL, "jdbc:postgresql://localhost:55432/minibank");
        IllegalStateException url = assertThrows(IllegalStateException.class, MinibankProperties::sqlUrl);
        assertTrue(url.getMessage().contains(MinibankProperties.SQL_URL), url.getMessage());

        System.setProperty(LEGACY_SQL_USER, "someone");
        assertThrows(IllegalStateException.class, MinibankProperties::sqlUser);

        System.setProperty(LEGACY_SQL_PASSWORD, "secret");
        assertThrows(IllegalStateException.class, MinibankProperties::sqlPassword);
    }

    @Test
    void theCurrentKeyWinsWhenBothNamesArePresent() {
        System.setProperty(LEGACY_SQL_URL, "jdbc:postgresql://localhost:5432/minibank");
        System.setProperty(MinibankProperties.SQL_URL, "jdbc:postgresql://localhost:55432/minibank");

        assertEquals("jdbc:postgresql://localhost:55432/minibank", MinibankProperties.sqlUrl());
    }
}
