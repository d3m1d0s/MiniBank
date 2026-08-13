package cz.vsb.minibank.infrastructure.json;

import cz.vsb.minibank.domain.DomainEvent;
import cz.vsb.minibank.domain.DomainEventBus;
import cz.vsb.minibank.domain.RecordsDomainEvents;
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

    /**
     * What a completed unit of work says to a caller that is still reading through it.
     *
     * Answering the read instead was a divergence between the two backends, and the JSON side
     * was the lax one. The identity map is empty by the time this could be asked, so the
     * repository fell through to the store and took a fresh hold of the store lock: a second,
     * unrelated transaction wearing the name of the one that had ended, answering with rows
     * that nothing in this unit of work can write back. {@code SqlUnitOfWork} could not do that
     * even by accident - its cleanup has closed the connection, so the same call arrives as a
     * wrapped SQLException - so code written and tested on JSON failed only in SQL. The strict
     * reading is the one both now take: a read through a transaction that has finished is a
     * mistake, and serving it hides the mistake rather than serving the caller.
     */
    private static final String COMPLETED_IDENTITY_MAP =
            "Cannot use the identity map of a completed UnitOfWork";

    private final JsonDataStore store;
    private final DomainEventBus events;
    private final Thread owner;
    private final Map<Class<?>, Map<Integer, Object>> identities = new HashMap<>();
    private final List<Runnable> mutations = new ArrayList<>();
    private boolean active = true;

    public JsonUnitOfWork(JsonDataStore store, DomainEventBus events) {
        this.store = store;
        this.events = Objects.requireNonNull(events, "events");
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
        if (!active) throw new IllegalStateException(COMPLETED_IDENTITY_MAP);
        var byType = identities.get(type);
        return byType == null ? null : (T) byType.get(id);
    }

    @Override public <T> void put(Class<T> type, int id, T obj) {
        requireOwner();
        if (!active) throw new IllegalStateException(COMPLETED_IDENTITY_MAP);
        identities.computeIfAbsent(type, k -> new HashMap<>()).put(id, obj);
    }

    @SuppressWarnings("unchecked")
    @Override public <T> Collection<T> all(Class<T> type) {
        requireOwner();
        if (!active) throw new IllegalStateException(COMPLETED_IDENTITY_MAP);
        var byType = identities.get(type);
        // Copied rather than returned live: a caller iterating it may load another aggregate,
        // and that lookup ends in a put on the very map being walked.
        return byType == null ? List.of() : List.copyOf((Collection<T>) byType.values());
    }

    @Override public void registerMutation(Runnable r) {
        requireOwner();
        // SqlUnitOfWork's wording rather than the "UoW is not active" this used to say. Both
        // backends already refused this; only the sentence differed.
        if (!active) throw new IllegalStateException(
                "Cannot register mutations on a completed UnitOfWork");
        mutations.add(r);
    }

    /**
     * Persists, releases the lock, and only then announces what happened.
     *
     * Two orderings matter here and they point the same way. Events are drained after
     * store.save() so that a transaction which failed to persist announces nothing - the defect
     * an aggregate that published from inside its own setter always had. And they are published
     * after finish(), which is to say outside the store lock, so an observer writing to the audit
     * log no longer blocks every other JSON transaction for the duration of a file write. That
     * lock is held for the whole transaction by design, and this is the one thing that no longer
     * needs to be inside it.
     *
     * A second commit is refused rather than ignored. This used to return quietly while
     * {@code SqlUnitOfWork.commit} threw, so a use case that committed twice was correct on the
     * JSON store and an HTTP 500 on PostgreSQL - the one divergence a caller could not find by
     * testing. There is no reading under which the second call is meaningful: the mutations are
     * cleared, the aggregates are drained and the store lock is gone, so it would persist
     * nothing and announce nothing. It is a caller that believes it still has a transaction.
     */
    @Override public void commit() {
        requireOwner();
        // Above the try, not inside it. finish() has already released the store lock, so a
        // refusal raised inside would be handled as a failed commit: discardChanges would re-read
        // the file over whatever the next transaction has in flight, and the finally would then
        // unlock a hold this thread no longer has, burying the refusal under an
        // IllegalMonitorStateException. SqlUnitOfWork guards ahead of its own try as well.
        if (!active) throw new IllegalStateException("UnitOfWork already completed");
        List<DomainEvent> happened;
        try {
            // The store lock is already held by this thread, so the buffered mutations
            // and the single save cannot interleave with another thread's reads or commits.
            for (Runnable r : mutations) r.run();
            store.save(); // single physical persist
            happened = drainDomainEvents();
        } catch (Exception e) {
            // The mutations above have already been applied to the shared Bundle. The next
            // transaction starts the moment finish() releases the lock and would persist
            // them, so put the store back to the last state that reached disk.
            store.discardChanges(e);
            throw new RuntimeException(e);
        } catch (Throwable failure) {
            // The same revert, for what an Exception does not cover.
            discardAfterFailure(failure);
            throw failure;
        } finally {
            finish();
        }
        events.publishAll(happened);
    }

    /**
     * Reverts a commit that failed with something no catch of {@code Exception} sees, without
     * losing what it failed with.
     *
     * An Error is not an Exception, and the finally above completes the unit of work whatever
     * escapes. A StackOverflowError out of Jackson part way through a save, an OutOfMemoryError,
     * an assertion inside a mutation: each one released the store lock with this transaction's
     * changes still sitting in the shared Bundle. The next transaction reads them as if they were
     * real and its own commit writes them to disk, so the payment this caller was told had failed
     * is persisted by whoever came next. {@code SqlUnitOfWork.commit} catches no more than this
     * one did and needs no equivalent: its cleanup closes the connection and the server discards
     * the uncommitted transaction itself. Its safety comes from the database. The JSON store has
     * no such backstop, because the cache is the only copy.
     *
     * What escaped is then rethrown untouched, because only the caller can judge what it means
     * for the process it is running in. So a revert that fails is attached as suppressed rather
     * than thrown in its place, the same way {@code SqlUnitOfWorkFactory} keeps the reason a
     * connection is being abandoned. {@code discardChanges} reports on an Exception, which is
     * precisely what this path does not have; it is handed a stand-in naming the real failure,
     * and the real failure is the one that propagates.
     */
    private void discardAfterFailure(Throwable failure) {
        try {
            store.discardChanges(new IllegalStateException("A commit failed with " + failure));
        } catch (Throwable revertFailure) {
            failure.addSuppressed(revertFailure);
        }
    }

    /**
     * Everything the aggregates in this transaction recorded, emptied out of them. See
     * {@code SqlUnitOfWork.drainDomainEvents}; the reasoning is identical and is not repeated.
     */
    private List<DomainEvent> drainDomainEvents() {
        List<DomainEvent> collected = new ArrayList<>();
        for (Map<Integer, Object> byId : identities.values()) {
            for (Object cached : byId.values()) {
                if (cached instanceof RecordsDomainEvents source) {
                    collected.addAll(source.drainDomainEvents());
                }
            }
        }
        return collected;
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
     * already-seeded file. TransferApplicationService's authorize path does the same thing a
     * second way: it commits and then throws from inside the try, so close() rolls back a unit
     * of work that has already completed. The invariant is: exactly one of commit()/rollback()
     * releases the store lock, and every later rollback() is a no-op.
     *
     * A later commit() is not, and the asymmetry is deliberate. rollback() after completion is
     * how try-with-resources is written here and says nothing about the caller; commit() after
     * completion is a caller that thinks it still has a transaction to persist. Only one of the
     * two can be forgiven without hiding the other.
     */
    private void finish() {
        active = false;
        mutations.clear();

        // Whatever is still recorded belongs to a transaction that is not committing - commit()
        // has already emptied the aggregates by the time it gets here. Dropped rather than left
        // on the object, which outlives this identity map.
        drainDomainEvents();

        identities.clear();
        store.unlock();
    }
}
