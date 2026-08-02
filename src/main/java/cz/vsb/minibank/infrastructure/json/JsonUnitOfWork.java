package cz.vsb.minibank.infrastructure.json;

import cz.vsb.minibank.infrastructure.uow.UnitOfWork;

import java.util.*;

/**
 * UnitOfWork implementation for JsonDataStore with an in-memory identity map
 * and buffered mutations persisted in a single save operation.
 *
 * One lock is involved, and it belongs to the store, not to this class. This unit of
 * work takes JsonDataStore's lock in the constructor and releases it in commit() or
 * rollback(), so everything a transaction reads and everything it writes back is one
 * atomic step. Holding it only across commit() would not be enough: a payment reads the
 * balance at the start of the request and writes it back at the end, so two concurrent
 * payments would each write back their own stale figure and one debit would vanish.
 *
 * The methods here used to be synchronized. That keyword is gone. It guarded identities,
 * mutations and active - state that is already covered by the store lock, since the only
 * thread that can reach these methods is the one that constructed the instance and is
 * therefore holding that lock. What it added instead was a second monitor acquired in the
 * opposite order inside commit(), which would have been a deadlock the day a unit of work
 * was shared between threads. The confinement it depended on is now checked outright.
 */
public final class JsonUnitOfWork implements UnitOfWork {
    private final JsonDataStore store;
    private final Thread owner;
    private final Map<Class<?>, Map<Integer, Object>> identities = new HashMap<>();
    private final List<Runnable> mutations = new ArrayList<>();
    private boolean active = true;

    public JsonUnitOfWork(JsonDataStore store) {
        this.store = store;
        this.owner = Thread.currentThread();
        // Held until commit() or rollback(). Every caller opens the unit of work in a
        // try-with-resources UowScope, and UowScope.close() ends one that was left open.
        store.lock();
    }

    /**
     * A JsonUnitOfWork belongs to the thread that created it.
     *
     * This is not a style rule. The store lock is held from the constructor and released
     * on completion, and ReentrantLock.unlock() from a thread that does not own the hold
     * throws. Confinement is also what makes the unsynchronised fields below safe. It has
     * always been true - UowContext is a ThreadLocal and every call site keeps the unit of
     * work in a local - so this check costs nothing and turns a future violation into a
     * clear failure instead of a corrupted identity map or an IllegalMonitorStateException
     * from somewhere unrelated.
     */
    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException(
                    "A JsonUnitOfWork may only be used by the thread that opened it (opened on '"
                            + owner.getName() + "', used from '" + Thread.currentThread().getName() + "')");
        }
    }

    @SuppressWarnings("unchecked")
    @Override public <T> T get(Class<T> type, int id) {
        requireOwner();
        var byType = identities.get(type);
        return byType == null ? null : (T) byType.get(id);
    }

    @Override public <T> void put(Class<T> type, int id, T obj) {
        requireOwner();
        identities.computeIfAbsent(type, k -> new HashMap<>()).put(id, obj);
    }

    @SuppressWarnings("unchecked")
    @Override public <T> Collection<T> all(Class<T> type) {
        requireOwner();
        var byType = identities.get(type);
        // Copied rather than returned live: a caller iterating it may load another aggregate,
        // and that lookup ends in a put on the very map being walked.
        return byType == null ? List.of() : List.copyOf((Collection<T>) byType.values());
    }

    @Override public void registerMutation(Runnable r) {
        requireOwner();
        if (!active) throw new IllegalStateException("UoW is not active");
        mutations.add(r);
    }

    @Override public void commit() {
        requireOwner();
        if (!active) return;
        try {
            // The store lock is already held by this thread, so the buffered mutations
            // and the single save cannot interleave with another thread's reads or commits.
            for (Runnable r : mutations) r.run();
            store.save(); // single physical persist
        } catch (Exception e) {
            // The mutations above have already been applied to the shared Bundle. The next
            // transaction starts the moment finish() releases the lock and would persist
            // them, so put the store back to the last state that reached disk.
            store.discardChanges(e);
            throw new RuntimeException(e);
        } finally {
            finish();
        }
    }

    @Override public void rollback() {
        requireOwner();
        if (!active) return;
        finish();
    }

    /**
     * Ends the transaction exactly once and releases the store lock.
     *
     * The active guard on rollback() is load-bearing, and not only on exception paths.
     * DemoScenario.seed() calls rollback() explicitly and then returns from inside its
     * try-with-resources block, so UowScope.close() calls rollback() a second time on a
     * completely ordinary run - and DemoUsersInitializer does that at startup against an
     * already-seeded file. The services call rollback() after a failed commit() for the
     * same reason. The invariant is: exactly one of commit()/rollback() releases the
     * store lock, and every later completion call is a no-op.
     */
    private void finish() {
        active = false;
        mutations.clear();
        identities.clear();
        store.unlock();
    }
}
