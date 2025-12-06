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
