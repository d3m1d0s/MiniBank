package cz.vsb.minibank.uow;

import cz.vsb.minibank.application.MinibankProperties;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Locale;

/**
 * Connection details for the PostgreSQL instance the SQL integration tests run against.
 * <p>
 * These tests truncate every table before each case, so they get their own keys and their
 * own default database rather than reusing {@link MinibankProperties}. Sharing one key
 * would mean a single override pointed both the application and the truncation at the same
 * database, which is how a plain {@code mvn test} came to wipe the demo logins.
 * <p>
 * {@code docker compose up -d} creates this database alongside the application one and
 * applies the schema to both, so no manual step is needed. Against a PostgreSQL of your
 * own, create it and apply {@code db/init/schema.sql} by hand. Without it the whole class
 * reports as skipped rather than failing.
 */
final class TestDatabase {

    static final String URL = "minibank.test.sql.url";
    static final String URL_DEFAULT = "jdbc:postgresql://localhost:5432/minibank_test";

    static final String USER = "minibank.test.sql.user";
    static final String USER_DEFAULT = "minibank";

    static final String PASSWORD = "minibank.test.sql.password";
    static final String PASSWORD_DEFAULT = "minibank";

    private static final int PROBE_TIMEOUT_SECONDS = 2;

    private static final String JDBC_PREFIX = "jdbc:postgresql:";

    private TestDatabase() {
    }

    static String url() {
        return System.getProperty(URL, URL_DEFAULT);
    }

    static String user() {
        return System.getProperty(USER, USER_DEFAULT);
    }

    static String password() {
        return System.getProperty(PASSWORD, PASSWORD_DEFAULT);
    }

    static Connection connect() throws Exception {
        return DriverManager.getConnection(url(), user(), password());
    }

    /**
     * True when a connection can be opened. Anything else means the tests are skipped, so
     * the reason is deliberately not distinguished: a missing server, a missing database
     * and a wrong password all mean the same thing to a developer without one.
     */
    static boolean isReachable() {
        int previousTimeout = DriverManager.getLoginTimeout();
        DriverManager.setLoginTimeout(PROBE_TIMEOUT_SECONDS);
        try (Connection ignored = connect()) {
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            DriverManager.setLoginTimeout(previousTimeout);
        }
    }

    static String unreachableMessage() {
        return "No PostgreSQL test database at " + url() + ". Create it and apply db/init/schema.sql,"
                + " or point the tests elsewhere with -D" + URL + "=...";
    }

    /**
     * The tests truncate everything they can reach, so running them against the database the
     * application is configured to use would destroy real data, including the demo logins
     * that {@code CASCADE} reaches through the foreign key on {@code users}.
     */
    static void requireSeparateFromApplicationDatabase() {
        String testUrl = url();
        String applicationUrl = MinibankProperties.sqlUrl();
        if (mayBeTheSameDatabase(testUrl, applicationUrl)) {
            throw new IllegalStateException(
                    "The SQL tests truncate every table and are pointed at " + testUrl
                            + ", which cannot be told apart from the application database ("
                            + MinibankProperties.SQL_URL + " = " + applicationUrl + "). Point -D"
                            + URL + " at a database with a name of its own, such as "
                            + URL_DEFAULT + ".");
        }
    }

    /**
     * True when the two urls cannot be shown to name different databases, which is the only
     * answer this guard may act on: a wrong "separate" costs an application database and a
     * wrong "same" costs a message asking the operator to rename the test database.
     * <p>
     * Only the database name decides it. Comparing the two urls as strings was the first
     * attempt and it let through every other spelling of one database: {@code 127.0.0.1}
     * against {@code localhost}, an appended {@code ?sslmode=...}, the host's real name, a
     * difference in case. Comparing host, port and name together reads as the stricter check
     * but is weaker exactly where this is used, because the application side of the comparison
     * is usually not the application's real url: the command line that starts the tests sets only
     * the test keys, so {@link MinibankProperties#SQL_URL} falls back to the compile-time default
     * on port 5432 while the documented compose setup publishes the server on 55432. Against that
     * default a triple comparison calls {@code jdbc:postgresql://localhost:55432/minibank}
     * separate and truncates the application database, which is the accident this guard exists for.
     * <p>
     * The name carries it alone, then. Within a cluster the name is the database's identity, so
     * two urls naming different databases are never one database; and a database called
     * {@code minibank} deserves refusing on any host, because nothing here can tell whose it is.
     * Asking the server which database it is connected to is the one check no spelling can fool,
     * but it costs a connection and this runs before the reachability probe, so on a machine
     * with no server the question has no answer - and a guard that has to interpret silence is
     * worse than one that reads a name.
     * <p>
     * A url of a shape this cannot read counts as the same database, for the same reason: not
     * being readable is not evidence of being separate.
     */
    static boolean mayBeTheSameDatabase(String testJdbcUrl, String applicationJdbcUrl) {
        String test = databaseNameIn(testJdbcUrl);
        String application = databaseNameIn(applicationJdbcUrl);
        return test == null || application == null || test.equals(application);
    }

    /**
     * The database a PostgreSQL JDBC url ends with, or null when the url carries no readable
     * name. Both the {@code //host:port/name} form and the bare {@code jdbc:postgresql:name}
     * form are accepted, since both are things an operator types.
     * <p>
     * Folded to lower case: an unquoted {@code CREATE DATABASE} lower-cases the name it is
     * given, so two urls differing only in case nearly always reach one database, and where a
     * quoted name really does make two, refusing costs a rename.
     */
    private static String databaseNameIn(String jdbcUrl) {
        if (jdbcUrl == null) {
            return null;
        }
        String text = jdbcUrl.trim();
        if (!text.regionMatches(true, 0, JDBC_PREFIX, 0, JDBC_PREFIX.length())) {
            return null;
        }

        String rest = text.substring(JDBC_PREFIX.length());
        int parameters = rest.indexOf('?');
        if (parameters >= 0) {
            rest = rest.substring(0, parameters);
        }
        if (rest.startsWith("//")) {
            // The authority runs to the next slash; an empty host is legal and means the
            // default, so the search starts after the two slashes rather than at them.
            int afterAuthority = rest.indexOf('/', 2);
            rest = afterAuthority < 0 ? "" : rest.substring(afterAuthority + 1);
        }

        return rest.isEmpty() || rest.indexOf('/') >= 0 ? null : rest.toLowerCase(Locale.ROOT);
    }
}
