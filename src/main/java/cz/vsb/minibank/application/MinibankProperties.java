package cz.vsb.minibank.application;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every configuration key the application reads, with its default.
 * <p>
 * The REST API resolves these through the Spring environment and the console entry
 * points read them as plain system properties, so one {@code -D} works for all of them.
 * {@link #LOG_FILE} is the exception and says why on itself.
 * Keeping the names and the defaults in one place is what stops the two paths from
 * drifting apart, which is how two different vocabularies for the same database
 * connection came to exist.
 */
public final class MinibankProperties {

    /** Which persistence backend to use: {@code json} or {@code sql}. */
    public static final String STORAGE = "minibank.storage";
    public static final String STORAGE_DEFAULT = "json";

    /** Location of the JSON store used by the console app and by the API in json mode. */
    public static final String JSON_PATH = "minibank.json.path";
    public static final String JSON_PATH_DEFAULT = "storage/data.json";

    /** Location of the demo runner's own store, deliberately separate from {@link #JSON_PATH}. */
    public static final String DEMO_PATH = "minibank.demo.path";
    public static final String DEMO_PATH_DEFAULT = "storage/demo.json";

    /** Opt-in: discard the demo store before running the demo scenario. */
    public static final String DEMO_RESET = "minibank.demo.reset";

    /**
     * Whether the SQL console creates the demo dataset and the demo logins on startup. Default
     * true, so the one-command demo keeps working straight after a clone and an absent key
     * changes nothing.
     * <p>
     * The SQL console and not the JSON one, because only the SQL console creates a credential.
     * {@code App} runs the same scenario against the JSON store and creates no user at all, and
     * the repository Bootstrap hands JSON mode keeps its users in memory, so that entry point
     * leaves no login behind for this key to withhold.
     * <p>
     * This is the console's half of what {@code spring.profiles.default=demo} does for the REST
     * API, and the pair exists on purpose rather than by omission. The console has no Spring
     * profiles, so there is no bean definition to gate there; and the profile decides more on
     * the API side than whether a seed runs, so folding the two into this one key would take
     * something away from the API rather than tidy the console. The two are not interchangeable
     * either: a profile only decides whether a bean is built, and cannot unwrite rows another
     * process has already committed to the same database, which is exactly why the console
     * needs a switch of its own.
     * <p>
     * Read the way {@link #DEMO_RESET} is read: {@code true} in any case is true, and every
     * other value, a malformed one included, is false. Only an absent key takes the default.
     */
    public static final String DEMO_ENABLED = "minibank.demo.enabled";

    public static final String SQL_URL = "minibank.sql.url";

    /**
     * The port is 55432, the one docker-compose.yml publishes, so a clone that has configured
     * nothing reaches the database this project ships with and no command has to carry the url.
     * PostgreSQL's own 5432 is deliberately not the default here: on a developer's machine that
     * port usually belongs to a server of their own, and a default that reaches it silently would
     * point the application at somebody else's data.
     */
    public static final String SQL_URL_DEFAULT = "jdbc:postgresql://localhost:55432/minibank";

    public static final String SQL_USER = "minibank.sql.user";
    public static final String SQL_USER_DEFAULT = "minibank";

    public static final String SQL_PASSWORD = "minibank.sql.password";
    public static final String SQL_PASSWORD_DEFAULT = "minibank";

    /**
     * Destination of the application log; the test run redirects it into target/.
     * <p>
     * The odd one out among these keys: {@link AppLogger} is reached from places that have no
     * Spring environment to ask, so it reads this as a system property and nothing else. The API
     * hands it the value its own environment resolved, which is what lets the key also be set in
     * application.properties or in a variable there. Everywhere else, only {@code -D} steers it.
     * <p>
     * The default is inside {@code storage/}, beside the JSON store, rather than at the root of
     * the project. Running the application in a clone used to drop a log file next to the pom,
     * where the only thing keeping it out of a commit was a line in .gitignore; under
     * {@code storage/} it lands in the directory the documents already call runtime state.
     */
    public static final String LOG_FILE = "minibank.log.file";
    public static final String LOG_FILE_DEFAULT = "storage/minibank.log";

    private static final String LEGACY_SQL_URL = "minibank.jdbcUrl";
    private static final String LEGACY_SQL_USER = "minibank.dbUser";
    private static final String LEGACY_SQL_PASSWORD = "minibank.dbPass";

    /**
     * The names these keys used to have, mapped to the names that replaced them.
     * <p>
     * Published because the two entry paths meet an old name at different moments and answer it
     * differently. The console reads these keys through {@link #readRenamed} below and refuses to
     * start; the API resolves them through placeholders that never reach this class, so it
     * recognises an old name at startup and warns. Both need the same list, and a list that
     * existed twice would let one of them stop recognising a name the other still refuses.
     * <p>
     * Insertion ordered so the warnings come out in the order the keys are declared above.
     */
    public static final Map<String, String> RENAMED_KEYS = renamedKeys();

    private static Map<String, String> renamedKeys() {
        Map<String, String> renamed = new LinkedHashMap<>();
        renamed.put(LEGACY_SQL_URL, SQL_URL);
        renamed.put(LEGACY_SQL_USER, SQL_USER);
        renamed.put(LEGACY_SQL_PASSWORD, SQL_PASSWORD);
        return Collections.unmodifiableMap(renamed);
    }

    private MinibankProperties() {
    }

    public static String storage() {
        return System.getProperty(STORAGE, STORAGE_DEFAULT);
    }

    public static String jsonPath() {
        return System.getProperty(JSON_PATH, JSON_PATH_DEFAULT);
    }

    public static String demoPath() {
        return System.getProperty(DEMO_PATH, DEMO_PATH_DEFAULT);
    }

    public static boolean demoReset() {
        return Boolean.getBoolean(DEMO_RESET);
    }

    public static boolean demoEnabled() {
        // Not Boolean.getBoolean, which reads an absent key as false and so cannot express a
        // default of true. The parse underneath is the same one, so a value that is neither
        // true nor false is false here exactly as it is for DEMO_RESET.
        return Boolean.parseBoolean(System.getProperty(DEMO_ENABLED, "true"));
    }

    public static String sqlUrl() {
        return readRenamed(SQL_URL, LEGACY_SQL_URL, SQL_URL_DEFAULT);
    }

    public static String sqlUser() {
        return readRenamed(SQL_USER, LEGACY_SQL_USER, SQL_USER_DEFAULT);
    }

    public static String sqlPassword() {
        return readRenamed(SQL_PASSWORD, LEGACY_SQL_PASSWORD, SQL_PASSWORD_DEFAULT);
    }

    /**
     * Reads a key that used to have a different name, refusing to start when only the old
     * name is present. Ignoring it would silently fall back to the default, and for the
     * database connection that default points at the instance docker compose publishes.
     */
    private static String readRenamed(String key, String legacyKey, String defaultValue) {
        if (System.getProperty(key) == null && System.getProperty(legacyKey) != null) {
            throw new IllegalStateException(
                    legacyKey + " was renamed to " + key + ". Ignoring it would fall back to "
                            + defaultValue + ", so it is refused instead.");
        }
        return System.getProperty(key, defaultValue);
    }
}
