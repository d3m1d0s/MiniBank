package cz.vsb.minibank.infrastructure.json.repo;

import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.infrastructure.json.JsonDataStore;
import cz.vsb.minibank.infrastructure.json.dto.JsonTransfer;
import cz.vsb.minibank.infrastructure.json.mapping.JsonMapper;
import cz.vsb.minibank.infrastructure.uow.UowContext;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class JsonTransferRepository implements TransferRepository {
    private final JsonDataStore store;
    public JsonTransferRepository(JsonDataStore store) { this.store = store; }

    @Override public int nextId() {
        return store.nextTransferId();
    }

    @Override public void add(Transfer t) {
        UnitOfWork uow = UowContext.current();
        if (uow != null) {
            uow.registerMutation(() -> store.data().transfers.add(JsonMapper.toDto(t)));
            uow.put(Transfer.class, t.id(), t);
        } else {
            store.data().transfers.add(JsonMapper.toDto(t));
            try { store.save(); } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Override public void save(Transfer t) {
        UnitOfWork uow = UowContext.current();
        Runnable mutate = () -> {
            var list = store.data().transfers;
            int idx = -1; for (int i=0;i<list.size();i++) if (list.get(i).id == t.id()) { idx=i; break; }
            JsonTransfer dto = JsonMapper.toDto(t);
            if (idx>=0) list.set(idx, dto); else list.add(dto);
        };
        if (uow != null) {
            uow.registerMutation(mutate);
            uow.put(Transfer.class, t.id(), t);
        } else {
            mutate.run();
            try { store.save(); } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Override public Optional<Transfer> byId(int id) {
        UnitOfWork uow = UowContext.current();
        if (uow != null) {
            Transfer cached = uow.get(Transfer.class, id);
            if (cached != null) return Optional.of(cached);
        }
        var found = store.data().transfers.stream().filter(x -> x.id == id).findFirst();
        if (found.isEmpty()) return Optional.empty();
        Transfer d = JsonMapper.toDomain(found.get());
        if (uow != null) uow.put(Transfer.class, d.id(), d);
        return Optional.of(d);
    }

    @Override public List<Transfer> bySourceAccount(int accountId) {
        UnitOfWork uow = UowContext.current();
        return store.data().transfers.stream()
                .filter(t -> t.sourceAccountId == accountId)
                .map(dto -> {
                    if (uow != null) {
                        Transfer cached = uow.get(Transfer.class, dto.id);
                        if (cached != null) return cached;
                    }
                    Transfer d = JsonMapper.toDomain(dto);
                    if (uow != null) uow.put(Transfer.class, d.id(), d);
                    return d;
                })
                .collect(Collectors.toList());
    }
}