package cz.vsb.minibank.application;

/**
 * Every configuration key the application reads, with its default.
 * <p>
 * The REST API resolves these through the Spring environment and the console entry
 * points read them as plain system properties, so one {@code -D} works for all of them.
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

    public static final String SQL_URL = "minibank.sql.url";
    public static final String SQL_URL_DEFAULT = "jdbc:postgresql://localhost:5432/minibank";

    public static final String SQL_USER = "minibank.sql.user";
    public static final String SQL_USER_DEFAULT = "minibank";

    public static final String SQL_PASSWORD = "minibank.sql.password";
    public static final String SQL_PASSWORD_DEFAULT = "minibank";

    /** Destination of the application log; the test run redirects it into target/. */
    public static final String LOG_FILE = "minibank.log.file";
    public static final String LOG_FILE_DEFAULT = "minibank.log";

    private static final String LEGACY_SQL_URL = "minibank.jdbcUrl";
    private static final String LEGACY_SQL_USER = "minibank.dbUser";
    private static final String LEGACY_SQL_PASSWORD = "minibank.dbPass";

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
