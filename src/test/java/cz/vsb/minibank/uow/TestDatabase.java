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
 * <p>
 * There is no default address, and that is the point. A default meant an unconfigured
 * {@code mvn test} went looking for a server on 5432, which on a developer's machine is
 * where a PostgreSQL of their own usually is, and these tests TRUNCATE whatever answers.
 * An address nobody typed is the one address this must never be pointed at, so an absent
 * {@link #URL} skips the database tests and the skip message names the key.
 */
final class TestDatabase {

    static final String URL = "minibank.test.sql.url";

    /**
     * What to point the tests at, an example and not a default: it appears only in the two
     * messages that ask for an address and never in the code that reads one. Same server and same
     * published port as the application's own default; the database name is the whole difference
     * between them, and the name is the only part the guard below reads.
     */
    static final String URL_EXAMPLE = "jdbc:postgresql://localhost:55432/minibank_test";

    static final String USER = "minibank.test.sql.user";
    static final String USER_DEFAULT = "minibank";

    static final String PASSWORD = "minibank.test.sql.password";
    static final String PASSWORD_DEFAULT = "minibank";

    private static final int PROBE_TIMEOUT_SECONDS = 2;

    private static final String JDBC_PREFIX = "jdbc:postgresql:";

    private TestDatabase() {
    }

    /** The configured address, or null when there is none and the tests must skip. */
    static String url() {
        return System.getProperty(URL);
    }

    static boolean isConfigured() {
        return url() != null;
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
     * <p>
     * An unconfigured run never opens anything: there is nowhere to open it to, and guessing
     * is what this class exists to stop.
     */
    static boolean isReachable() {
        if (!isConfigured()) {
            return false;
        }
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

    /**
     * Why the database tests are being skipped, in the words of whichever of the two reasons
     * applies. An unconfigured run is the common one and the only one that is nobody's mistake,
     * so it names the key and shows the value to give it rather than describing an absence.
     */
    static String unreachableMessage() {
        if (!isConfigured()) {
            return "The SQL tests need a database of their own and none was named, so they are"
                    + " skipped. Start the one this project ships with (docker compose up -d) and"
                    + " run: mvn -B test -D" + URL + "=" + URL_EXAMPLE;
        }
        return "No PostgreSQL test database at " + url() + ". Create it and apply db/init/schema.sql,"
                + " or point the tests elsewhere with -D" + URL + "=...";
    }

    /**
     * The tests truncate everything they can reach, so running them against the database the
     * application is configured to use would destroy real data, including the demo logins
     * that {@code CASCADE} reaches through the foreign key on {@code users}.
     * <p>
     * An unconfigured run passes here and is stopped by {@link #isReachable()} instead. It is not
     * pointed at anything, so there is nothing to compare and nothing to destroy; refusing it
     * would turn a skip into a failed build on every clone that has no database.
     */
    static void requireSeparateFromApplicationDatabase() {
        if (!isConfigured()) {
            return;
        }
        String testUrl = url();
        String applicationUrl = MinibankProperties.sqlUrl();
        if (mayBeTheSameDatabase(testUrl, applicationUrl)) {
            throw new IllegalStateException(
                    "The SQL tests truncate every table and are pointed at " + testUrl
                            + ", which cannot be told apart from the application database ("
                            + MinibankProperties.SQL_URL + " = " + applicationUrl + "). Point -D"
                            + URL + " at a database with a name of its own, such as "
                            + URL_EXAMPLE + ".");
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
     * the test keys, so {@link MinibankProperties#SQL_URL} falls back to its compile-time default.
     * Every spelling of that one address other than the literal string it holds then reads as a
     * different host or a different port, so a triple comparison calls the application database
     * separate and truncates it, which is the accident this guard exists for.
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
