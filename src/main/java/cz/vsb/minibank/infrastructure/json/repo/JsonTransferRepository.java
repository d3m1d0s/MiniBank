package cz.vsb.minibank.infrastructure.json.repo;

import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.repository.FraudAlertRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.infrastructure.json.JsonDataStore;
import cz.vsb.minibank.infrastructure.json.dto.JsonTransfer;
import cz.vsb.minibank.infrastructure.json.mapping.JsonMapper;
import cz.vsb.minibank.infrastructure.uow.UowContext;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * JSON-backed implementation of {@link TransferRepository}.
 * Uses {@link JsonDataStore} as the persistence backend and participates
 * in the UnitOfWork / Identity Map mechanism when a UnitOfWork is active.
 */
public class JsonTransferRepository implements TransferRepository {
    private final JsonDataStore store;

    public JsonTransferRepository(JsonDataStore store) {
        this.store = store;
    }

    @Override
    public int nextId() {
        return store.nextTransferId();
    }

    @Override
    public void add(Transfer t) {
        UnitOfWork uow = UowContext.current();
        // A bare ArrayList.add was the dropped-element site: two concurrent adds can write
        // the same backing slot and increment size once, which is how a fraud alert ended
        // up pointing at a transfer id that is not on disk.
        Runnable mutate = () -> store.data().transfers.add(JsonMapper.toDto(t));
        if (uow != null) {
            // Runs during commit(), with the store lock already held by this thread.
            uow.registerMutation(mutate);
            uow.put(Transfer.class, t.id(), t);
        } else {
            store.mutateAndSave(mutate);
        }
    }

    @Override
    public void save(Transfer t) {
        UnitOfWork uow = UowContext.current();
        Runnable mutate = () -> {
            var list = store.data().transfers;
            int idx = -1;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id == t.id()) {
                    idx = i;
                    break;
                }
            }
            JsonTransfer dto = JsonMapper.toDto(t);
            if (idx >= 0) {
                list.set(idx, dto);
            } else {
                list.add(dto);
            }
        };
        if (uow != null) {
            uow.registerMutation(mutate);
            uow.put(Transfer.class, t.id(), t);
        } else {
            // The index scan inside mutate calls size() and get(i) separately; a concurrent
            // add leaving a null hole makes get(i).id throw.
            store.mutateAndSave(mutate);
        }
    }

    @Override
    public Optional<Transfer> byId(int id) {
        UnitOfWork uow = UowContext.current();
        if (uow != null) {
            Transfer cached = uow.get(Transfer.class, id);
            if (cached != null) {
                return Optional.of(cached);
            }
        }
        // This is the read that returned empty for a just-committed row and surfaced as
        // HTTP 500 "Transfer not found".
        return store.read(bundle -> {
            var found = bundle.transfers.stream().filter(x -> x.id == id).findFirst();
            if (found.isEmpty()) {
                return Optional.<Transfer>empty();
            }
            Transfer d = JsonMapper.toDomain(found.get(), store);
            if (uow != null) {
                uow.put(Transfer.class, d.id(), d);
            }
            return Optional.of(d);
        });
    }

    @Override
    public List<Transfer> bySourceAccount(int accountId) {
        UnitOfWork uow = UowContext.current();
        return store.read(bundle -> bundle.transfers.stream()
                .filter(t -> t.sourceAccountId == accountId)
                .map(dto -> {
                    if (uow != null) {
                        Transfer cached = uow.get(Transfer.class, dto.id);
                        if (cached != null) {
                            return cached;
                        }
                    }
                    Transfer d = JsonMapper.toDomain(dto, store);
                    if (uow != null) {
                        uow.put(Transfer.class, d.id(), d);
                    }
                    return d;
                })
                .collect(Collectors.toList()));
    }
}
