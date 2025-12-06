package cz.vsb.minibank.infrastructure.uow;

/**
 * Factory for creating new UnitOfWork instances.
 */
public interface UnitOfWorkFactory {

    /**
     * Opens a new UnitOfWork.
     * In SQL-based implementations this typically maps to a new transaction or connection scope.
     *
     * @return newly created UnitOfWork
     */
    UnitOfWork begin();
}
