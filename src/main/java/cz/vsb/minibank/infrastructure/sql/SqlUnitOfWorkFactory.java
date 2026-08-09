package cz.vsb.minibank.infrastructure.sql;

import cz.vsb.minibank.domain.DomainEventBus;
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

    @Override
    public UnitOfWork begin() {
        try {
            Connection conn = DriverManager.getConnection(url, user, password);
            conn.setAutoCommit(false);
            return new SqlUnitOfWork(conn, events);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open SQL UnitOfWork", e);
        }
    }
}
