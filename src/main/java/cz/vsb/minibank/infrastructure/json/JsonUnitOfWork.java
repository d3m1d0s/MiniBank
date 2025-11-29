package cz.vsb.minibank.infrastructure.json;

import cz.vsb.minibank.infrastructure.json.JsonDataStore;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;

import java.util.*;

public final class JsonUnitOfWork implements UnitOfWork {
    private final JsonDataStore store;
    private final Map<Class<?>, Map<Integer, Object>> identities = new HashMap<>();
    private final List<Runnable> mutations = new ArrayList<>();
    private boolean active = true;

    public JsonUnitOfWork(JsonDataStore store) { this.store = store; }

    @SuppressWarnings("unchecked")
    @Override public synchronized <T> T get(Class<T> type, int id) {
        var byType = identities.get(type);
        return byType == null ? null : (T) byType.get(id);
    }

    @Override public synchronized <T> void put(Class<T> type, int id, T obj) {
        identities.computeIfAbsent(type, k -> new HashMap<>()).put(id, obj);
    }

    @Override public synchronized void registerMutation(Runnable r) {
        if (!active) throw new IllegalStateException("UoW is not active");
        mutations.add(r);
    }

    @Override public synchronized void commit() {
        if (!active) return;
        try {
            for (Runnable r : mutations) r.run();
            store.save(); // single physical persist
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            active = false;
            mutations.clear();
            identities.clear();
        }
    }

    @Override public synchronized void rollback() {
        active = false;
        mutations.clear();
        identities.clear();
    }
}
