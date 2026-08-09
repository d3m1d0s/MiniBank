package cz.vsb.minibank.infrastructure.sql;

import cz.vsb.minibank.domain.DomainEvent;
import cz.vsb.minibank.domain.DomainEventBus;
import cz.vsb.minibank.domain.RecordsDomainEvents;
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
    private final DomainEventBus events;
    private final Map<Key, Object> identityMap = new HashMap<>();
    private final List<Runnable> mutations = new ArrayList<>();
    private boolean completed = false;

    public SqlUnitOfWork(Connection connection, DomainEventBus events) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.events = Objects.requireNonNull(events, "events");
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

    @SuppressWarnings("unchecked")
    @Override
    public <T> Collection<T> all(Class<T> type) {
        // Copied rather than returned live: a caller iterating it may load another aggregate,
        // and that lookup ends in a put on the very map being walked.
        List<T> result = new ArrayList<>();
        for (Map.Entry<Key, Object> entry : identityMap.entrySet()) {
            if (entry.getKey().type.equals(type)) {
                result.add((T) entry.getValue());
            }
        }
        return result;
    }

    @Override
    public void registerMutation(Runnable r) {
        if (completed) {
            throw new IllegalStateException("Cannot register mutations on a completed UnitOfWork");
        }
        mutations.add(Objects.requireNonNull(r, "mutation"));
    }

    /**
     * Executes the buffered mutations, commits, and only then announces what happened.
     *
     * The order is the point. Both repositories defer their writes to here, so an aggregate that
     * published as it mutated was announcing a change that had not been written yet and, on any
     * path that threw, never would be - a rolled-back transaction left audit lines for status
     * transitions that never reached the database. Draining after connection.commit() means an
     * event exists only if the row does.
     *
     * Published after cleanup rather than inside the try, so the connection is already back and
     * an observer that throws cannot be mistaken for a failed transaction. It cannot roll one
     * back either: by then there is nothing to roll back, and that asymmetry is real - a failing
     * audit writer loses its line and the money still moves.
     */
    @Override
    public void commit() {
        if (completed) {
            throw new IllegalStateException("UnitOfWork already completed");
        }
        List<DomainEvent> happened;
        try {
            // Execute all buffered mutations inside a single DB transaction
            for (Runnable mutation : mutations) {
                mutation.run();
            }
            connection.commit();
            happened = drainDomainEvents();
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
        events.publishAll(happened);
    }

    /**
     * Everything the aggregates in this transaction recorded, emptied out of them.
     *
     * Walks the identity map, which is the set of aggregates this transaction touched. An
     * aggregate that was mutated but never saved is still in there and its events are still
     * published, which is correct: the identity map is what a transaction knows about, and
     * nothing mutates an aggregate it does not intend to save.
     */
    private List<DomainEvent> drainDomainEvents() {
        List<DomainEvent> collected = new ArrayList<>();
        for (Object cached : identityMap.values()) {
            if (cached instanceof RecordsDomainEvents source) {
                collected.addAll(source.drainDomainEvents());
            }
        }
        return collected;
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

        // Whatever is still recorded belongs to a transaction that is not committing - commit()
        // has already emptied the aggregates by the time it gets here. Dropped rather than left
        // in place, because the identity map is per transaction but the object is not: a caller
        // holding the same instance into a second unit of work would otherwise have it publish
        // the first one's changes as the second one's.
        drainDomainEvents();

        identityMap.clear();
        try {
            connection.close();
        } catch (SQLException e) {
            // ignore
        }
    }
}
