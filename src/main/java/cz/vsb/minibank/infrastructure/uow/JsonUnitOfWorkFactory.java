package cz.vsb.minibank.infrastructure.uow;

import cz.vsb.minibank.infrastructure.json.JsonDataStore;

public final class JsonUnitOfWorkFactory implements UnitOfWorkFactory {
    private final JsonDataStore store;
    public JsonUnitOfWorkFactory(JsonDataStore store) { this.store = store; }
    @Override public UnitOfWork begin() { return new JsonUnitOfWork(store); }
}
