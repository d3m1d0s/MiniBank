package cz.vsb.minibank.infrastructure.uow;

public final class UowContext {
    private static final ThreadLocal<UnitOfWork> CURRENT = new ThreadLocal<>();

    private UowContext() {}

    public static UnitOfWork current() { return CURRENT.get(); }

    // Returns the previous Unit of Work so nested scopes can restore it correctly.
    public static UnitOfWork set(UnitOfWork u) {
        UnitOfWork prev = CURRENT.get();
        if (u == null) CURRENT.remove(); else CURRENT.set(u);
        return prev;
    }
}
