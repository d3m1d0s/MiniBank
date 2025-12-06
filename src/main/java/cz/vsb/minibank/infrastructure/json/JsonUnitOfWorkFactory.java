package cz.vsb.minibank.infrastructure.json;

import cz.vsb.minibank.infrastructure.json.JsonDataStore;
import cz.vsb.minibank.infrastructure.json.JsonUnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;
import cz.vsb.minibank.infrastructure.uow.UnitOfWorkFactory;

/**
 * UnitOfWorkFactory that creates JSON-based units of work over a shared JsonDataStore.
 */
public final class JsonUnitOfWorkFactory implements UnitOfWorkFactory {

    private final JsonDataStore store;

    public JsonUnitOfWorkFactory(JsonDataStore store) {
        this.store = store;
    }

    /**
     * Starts a new UnitOfWork operating on the same JsonDataStore instance.
     */
    @Override
    public UnitOfWork begin() {
        return new JsonUnitOfWork(store);
    }
}
