package cz.vsb.minibank.infrastructure.uow;

public interface UnitOfWork {
    <T> T get(Class<T> type, int id);           // Identity Map: получить из кэша, либо null
    <T> void put(Class<T> type, int id, T obj); // Identity Map: положить в кэш
    void registerMutation(Runnable r);          // Отложенная операция записи/обновления/удаления
    void commit();                              // Выполнить все мутации + persist
    void rollback();                            // Отменить (очистить буфер)
}
