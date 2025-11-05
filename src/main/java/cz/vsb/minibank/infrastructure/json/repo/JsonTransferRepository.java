package cz.vsb.minibank.infrastructure.json.repo;

import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.infrastructure.json.JsonDataStore;
import cz.vsb.minibank.infrastructure.json.dto.JsonTransfer;
import cz.vsb.minibank.infrastructure.json.mapping.JsonMapper;

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
        store.data().transfers.add(JsonMapper.toDto(t));
        try { store.save(); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Override public void save(Transfer t) {
        var list = store.data().transfers;
        var idx = -1; for (int i=0;i<list.size();i++) if (list.get(i).id == t.id()) { idx=i; break; }
        JsonTransfer dto = JsonMapper.toDto(t);
        if (idx>=0) list.set(idx, dto); else list.add(dto);
        try { store.save(); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Override public Optional<Transfer> byId(int id) {
        return store.data().transfers.stream().filter(t -> t.id == id).findFirst().map(JsonMapper::toDomain);
    }

    @Override public List<Transfer> bySourceAccount(int accountId) {
        return store.data().transfers.stream().filter(t -> t.sourceAccountId == accountId)
                .map(JsonMapper::toDomain).collect(Collectors.toList());
    }
}