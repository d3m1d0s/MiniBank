package cz.vsb.minibank.infrastructure.uow;

public final class UowScope implements AutoCloseable {
    private final UnitOfWork prev;
    public UowScope(UnitOfWork uow) { this.prev = UowContext.set(uow); }
    @Override public void close() { UowContext.set(prev); }
}
