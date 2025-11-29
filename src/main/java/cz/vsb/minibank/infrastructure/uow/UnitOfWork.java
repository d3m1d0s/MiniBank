package cz.vsb.minibank.infrastructure.uow;

public interface UnitOfWork {
    <T> T get(Class<T> type, int id);           // Identity Map: get from cache, or null
    <T> void put(Class<T> type, int id, T obj); // Identity Map: put into cache
    void registerMutation(Runnable r);          // Deferred write/update/delete operation
    void commit();                              // Execute all mutations and persist
    void rollback();                            // Cancel (clear buffer)
}
