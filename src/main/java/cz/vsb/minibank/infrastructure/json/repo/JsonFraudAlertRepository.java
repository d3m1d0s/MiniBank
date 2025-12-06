package cz.vsb.minibank.infrastructure.json.repo;

import cz.vsb.minibank.domain.FraudAlert;
import cz.vsb.minibank.domain.repository.FraudAlertRepository;
import cz.vsb.minibank.infrastructure.json.JsonDataStore;
import cz.vsb.minibank.infrastructure.json.dto.JsonFraudAlert;
import cz.vsb.minibank.infrastructure.json.mapping.JsonMapper;
import cz.vsb.minibank.infrastructure.uow.UowContext;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * JSON-backed implementation of {@link FraudAlertRepository}.
 * Uses {@link JsonDataStore} as the persistence backend and participates
 * in the UnitOfWork / Identity Map mechanism when a UnitOfWork is active.
 */
public class JsonFraudAlertRepository implements FraudAlertRepository {

    private final JsonDataStore store;

    public JsonFraudAlertRepository(JsonDataStore store) {
        this.store = store;
    }

    @Override
    public int nextId() {
        return store.nextFraudAlertId();
    }

    @Override
    public void add(FraudAlert a) {
        UnitOfWork uow = UowContext.current();
        Runnable mutate = () -> store.data().fraudAlerts.add(JsonMapper.toDto(a));
        if (uow != null) {
            uow.registerMutation(mutate);
            uow.put(FraudAlert.class, a.id(), a);
        } else {
            mutate.run();
            try {
                store.save();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void save(FraudAlert a) {
        UnitOfWork uow = UowContext.current();
        Runnable mutate = () -> {
            var list = store.data().fraudAlerts;
            int idx = -1;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id == a.id()) {
                    idx = i;
                    break;
                }
            }
            JsonFraudAlert dto = JsonMapper.toDto(a);
            if (idx >= 0) {
                list.set(idx, dto);
            } else {
                list.add(dto);
            }
        };
        if (uow != null) {
            uow.registerMutation(mutate);
            uow.put(FraudAlert.class, a.id(), a);
        } else {
            mutate.run();
            try {
                store.save();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public Optional<FraudAlert> byId(int id) {
        UnitOfWork uow = UowContext.current();
        if (uow != null) {
            FraudAlert cached = uow.get(FraudAlert.class, id);
            if (cached != null) return Optional.of(cached);
        }
        var f = store.data().fraudAlerts.stream().filter(x -> x.id == id).findFirst();
        if (f.isEmpty()) return Optional.empty();
        FraudAlert d = JsonMapper.toDomain(f.get());
        if (uow != null) uow.put(FraudAlert.class, d.id(), d);
        return Optional.of(d);
    }

    @Override
    public Optional<FraudAlert> byTransferId(int transferId) {
        var uow = UowContext.current();
        var f = store.data().fraudAlerts.stream().filter(x -> x.transferId == transferId).findFirst();
        if (f.isEmpty()) return Optional.empty();
        FraudAlert d = JsonMapper.toDomain(f.get());
        if (uow != null) uow.put(FraudAlert.class, d.id(), d);
        return Optional.of(d);
    }

    @Override
    public List<FraudAlert> all() {
        UnitOfWork uow = UowContext.current();
        return store.data().fraudAlerts.stream()
                .map(dto -> {
                    if (uow != null) {
                        FraudAlert cached = uow.get(FraudAlert.class, dto.id);
                        if (cached != null) return cached;
                    }
                    FraudAlert d = JsonMapper.toDomain(dto);
                    if (uow != null) uow.put(FraudAlert.class, d.id(), d);
                    return d;
                })
                .collect(Collectors.toList());
    }
}
