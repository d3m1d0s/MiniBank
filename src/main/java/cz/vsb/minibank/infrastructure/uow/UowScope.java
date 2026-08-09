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
     * The unit of work this scope bound to the current thread.
     *
     * Exists so the unit of work can be opened inside the resource list -
     * {@code try (UowScope scope = new UowScope(factory.begin()))} - rather than one statement
     * before it. {@code UnitOfWork} is not {@code AutoCloseable}, so it cannot be a resource
     * itself, and without this accessor a caller that inlined the call would have no way to
     * commit. The point of inlining is that a unit of work can no longer exist unbound: the
     * advice {@code JsonUnitOfWorkFactory} gives its callers becomes something they cannot
     * forget to follow.
     */
    public UnitOfWork uow() {
        return uow;
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
