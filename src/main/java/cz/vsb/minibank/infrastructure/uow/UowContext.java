package cz.vsb.minibank.infrastructure.uow;

/**
 * Thread-local holder for the current UnitOfWork.
 */
public final class UowContext {
    private static final ThreadLocal<UnitOfWork> CURRENT = new ThreadLocal<>();

    private UowContext() {}

    /**
     * Returns the UnitOfWork bound to the current thread, or null if none is set.
     */
    public static UnitOfWork current() {
        return CURRENT.get();
    }

    /**
     * Sets the current UnitOfWork and returns the previous one so nested scopes can restore it.
     *
     * @param u unit of work to bind, or null to clear
     * @return previously bound unit of work, or null
     */
    public static UnitOfWork set(UnitOfWork u) {
        UnitOfWork prev = CURRENT.get();
        if (u == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(u);
        }
        return prev;
    }
}
