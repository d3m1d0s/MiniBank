package cz.vsb.minibank.infrastructure.sql;

import cz.vsb.minibank.infrastructure.uow.UnitOfWork;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/**
 * Unit of Work implementation backed by a single JDBC connection.
 * Holds an Identity Map and a list of deferred mutations.
 */
public final class SqlUnitOfWork implements UnitOfWork {

    private static final class Key {
        private final Class<?> type;
        private final int id;

        Key(Class<?> type, int id) {
            this.type = Objects.requireNonNull(type, "type");
            this.id = id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return id == key.id && type.equals(key.type);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, id);
        }
    }

    private final Connection connection;
    private final Map<Key, Object> identityMap = new HashMap<>();
    private final List<Runnable> mutations = new ArrayList<>();
    private boolean completed = false;

    public SqlUnitOfWork(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * Exposes the underlying JDBC connection for repositories.
     * Repositories must NOT close this connection.
     */
    public Connection connection() {
        return connection;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> type, int id) {
        return (T) identityMap.get(new Key(type, id));
    }

    @Override
    public <T> void put(Class<T> type, int id, T obj) {
        identityMap.put(new Key(type, id), obj);
    }

    @Override
    public void registerMutation(Runnable r) {
        if (completed) {
            throw new IllegalStateException("Cannot register mutations on a completed UnitOfWork");
        }
        mutations.add(Objects.requireNonNull(r, "mutation"));
    }

    @Override
    public void commit() {
        if (completed) {
            throw new IllegalStateException("UnitOfWork already completed");
        }
        try {
            // Execute all buffered mutations inside a single DB transaction
            for (Runnable mutation : mutations) {
                mutation.run();
            }
            connection.commit();
        } catch (RuntimeException e) {
            // any failure -> rollback DB transaction as well
            try {
                connection.rollback();
            } catch (SQLException ex) {
                // best effort; rethrow original exception
            }
            throw e;
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException ex) {
                // ignore
            }
            throw new RuntimeException("Failed to commit SQL UnitOfWork", e);
        } finally {
            cleanup();
        }
    }

    @Override
    public void rollback() {
        if (completed) return;
        try {
            connection.rollback();
        } catch (SQLException e) {
            // ignore, nothing we can reasonably do here
        } finally {
            cleanup();
        }
    }

    private void cleanup() {
        completed = true;
        mutations.clear();
        identityMap.clear();
        try {
            connection.close();
        } catch (SQLException e) {
            // ignore
        }
    }
}
