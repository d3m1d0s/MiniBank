package cz.vsb.minibank.infrastructure.sql;

import cz.vsb.minibank.domain.event.DomainEventBus;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Factory for SQL-backed Units of Work (PostgreSQL).
 */
public final class SqlUnitOfWorkFactory implements UnitOfWorkFactory {

    private final String url;
    private final String user;
    private final String password;
    private final DomainEventBus events;

    static {
        try {
            // Ensure PostgreSQL driver is loaded
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("PostgreSQL JDBC driver not found on classpath", e);
        }
    }

    public SqlUnitOfWorkFactory(String url, String user, String password, DomainEventBus events) {
        this.url = Objects.requireNonNull(url, "url");
        this.user = Objects.requireNonNull(user, "user");
        this.password = Objects.requireNonNull(password, "password");
        this.events = Objects.requireNonNull(events, "events");
    }

    /**
     * Opens a connection, takes it out of auto-commit, and gives it to a unit of work.
     *
     * The two steps are separated because only the second one leaves something behind when it
     * fails. There is no pool here by design: every unit of work opens its own
     * DriverManager connection and {@link SqlUnitOfWork} closes it when the transaction ends.
     * A connection that never reaches that constructor is therefore closed by nobody, and a
     * server that drops the socket between the open and the configure call is exactly the case
     * that produces one. Each such failure held a PostgreSQL backend slot for as long as the
     * process ran, so a flapping database and a caller that retries could walk the server up to
     * max_connections and lock out everything else that wanted in.
     */
    @Override
    public UnitOfWork begin() {
        Connection conn;
        try {
            conn = DriverManager.getConnection(url, user, password);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open SQL UnitOfWork", e);
        }
        try {
            conn.setAutoCommit(false);
        } catch (SQLException e) {
            closeAfterFailure(conn, e);
            throw new RuntimeException("Failed to open SQL UnitOfWork", e);
        }
        return new SqlUnitOfWork(conn, events);
    }

    /**
     * Releases a connection that is being abandoned, without losing why it is being abandoned.
     *
     * A close on a connection the server has already dropped can itself fail, and letting that
     * out would replace the real reason with the noise it caused. The second failure is attached
     * to the first as a suppressed exception instead, so the caller still sees the SQLException
     * that started it and a reader of the stack trace still learns that the close did not take.
     */
    private static void closeAfterFailure(Connection conn, SQLException failure) {
        try {
            conn.close();
        } catch (SQLException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
