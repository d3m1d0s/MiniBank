package cz.vsb.minibank.infrastructure.json;

import cz.vsb.minibank.infrastructure.json.JsonDataStore;
import cz.vsb.minibank.infrastructure.json.JsonUnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;

public final class JsonUnitOfWorkFactory implements UnitOfWorkFactory {
    private final JsonDataStore store;
    public JsonUnitOfWorkFactory(JsonDataStore store) { this.store = store; }
    @Override public UnitOfWork begin() { return new JsonUnitOfWork(store); }
}
