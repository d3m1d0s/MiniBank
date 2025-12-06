package cz.vsb.minibank.infrastructure.uow;

/**
 * Scope helper that installs a UnitOfWork for the current thread
 * and restores the previous one when closed.
 */
public final class UowScope implements AutoCloseable {
    private final UnitOfWork prev;

    /**
     * Creates a new scope and binds the given UnitOfWork to the current thread.
     *
     * @param uow unit of work to use within this scope
     */
    public UowScope(UnitOfWork uow) {
        this.prev = UowContext.set(uow);
    }

    /**
     * Restores the previously active UnitOfWork.
     */
    @Override
    public void close() {
        UowContext.set(prev);
    }
}
