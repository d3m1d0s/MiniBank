package cz.vsb.minibank.uow;

import cz.vsb.minibank.application.MinibankProperties;

import java.sql.Connection;
import java.sql.DriverManager;

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
        if (url().equals(MinibankProperties.sqlUrl())) {
            throw new IllegalStateException(
                    "The SQL tests truncate every table and are pointed at " + url()
                            + ", which is also the application database (" + MinibankProperties.SQL_URL
                            + "). Point -D" + URL + " at a database of their own.");
        }
    }
}
