package cz.vsb.minibank.infrastructure.uow;

public final class UowContext {
    private static final ThreadLocal<UnitOfWork> CURRENT = new ThreadLocal<>();
    private UowContext() {}
    public static UnitOfWork current() { return CURRENT.get(); }
    /** @return предыдущий UoW (чтобы корректно восстанавливать вложенные скопы, если появятся) */
    public static UnitOfWork set(UnitOfWork u) {
        UnitOfWork prev = CURRENT.get();
        if (u == null) CURRENT.remove(); else CURRENT.set(u);
        return prev;
    }
}
