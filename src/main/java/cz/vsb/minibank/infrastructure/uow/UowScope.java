package cz.vsb.minibank.infrastructure.uow;

/**
 * Scope helper that installs a UnitOfWork for the current thread
 * and restores the previous one when closed.
 */
public final class UowScope implements AutoCloseable {
    private final UnitOfWork uow;
    private final UnitOfWork prev;

    /**
     * Creates a new scope and binds the given UnitOfWork to the current thread.
     *
     * @param uow unit of work to use within this scope
     */
    public UowScope(UnitOfWork uow) {
        this.uow = uow;
        try {
            this.prev = UowContext.set(uow);
        } catch (Throwable failedToBind) {
            // The unit of work is already open - a JSON one is already holding the store
            // lock - and nothing else will ever close it, because this constructor is
            // what makes close() reachable.
            if (uow != null) {
                uow.rollback();
            }
            throw failedToBind;
        }
    }

    /**
     * Restores the previously active UnitOfWork and ends one that was left open.
     *
     * Leaving the block without committing or rolling back does not just lose the
     * buffered work: a JSON unit of work holds the store lock until it completes, so an
     * Error escaping the try block would wedge the store for every other thread. Both
     * implementations treat rollback() on a completed unit of work as a no-op, so the
     * normal commit path is unaffected.
     */
    @Override
    public void close() {
        UowContext.set(prev);
        if (uow != null) {
            uow.rollback();
        }
    }
}
