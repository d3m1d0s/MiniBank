package cz.vsb.minibank.infrastructure.uow;

/**
 * Unit of Work abstraction combining an identity map and deferred persistence.
 */
public interface UnitOfWork {

    /**
     * Returns an object from the identity map or null when it is not cached.
     *
     * @param type aggregate type
     * @param id   technical identifier
     */
    <T> T get(Class<T> type, int id);

    /**
     * Stores an object in the identity map for the duration of this unit of work.
     *
     * @param type aggregate type
     * @param id   technical identifier
     * @param obj  instance to cache
     */
    <T> void put(Class<T> type, int id, T obj);

    /**
     * Returns every instance of this type currently in the identity map.
     *
     * The map is keyed by id, which answers "give me this row". A lookup by any other key -
     * an account's IBAN - cannot ask it anything, and that matters because both backends
     * defer their writes to commit: an aggregate created earlier in this same transaction has
     * no row yet, so a store query cannot see it either. Without this the two questions
     * disagree, and an account opened and paid in one unit of work looks like it belongs to
     * somebody else's bank.
     *
     * @return a snapshot that the caller may iterate freely; never null
     */
    <T> java.util.Collection<T> all(Class<T> type);

    /**
     * Registers a deferred mutation to be executed on commit.
     *
     * @param r mutation callback
     */
    void registerMutation(Runnable r);

    /**
     * Executes all registered mutations and persists changes.
     */
    void commit();

    /**
     * Discards all registered mutations without persisting anything.
     */
    void rollback();
}
