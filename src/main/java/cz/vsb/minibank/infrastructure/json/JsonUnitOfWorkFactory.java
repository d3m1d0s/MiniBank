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
     *
     * The returned unit of work already holds the store lock, so JSON transactions run
     * one at a time and this call blocks while another transaction is open. The lock is
     * released by commit() or rollback(), whichever the caller reaches first. Bind the
     * result to a UowScope immediately, so an escaping exception cannot leave it open.
     */
    @Override
    public UnitOfWork begin() {
        return new JsonUnitOfWork(store);
    }
}
