package cz.vsb.minibank.infrastructure.json.repo;

import cz.vsb.minibank.domain.FraudAlert;
import cz.vsb.minibank.domain.repository.FraudAlertRepository;
import cz.vsb.minibank.infrastructure.json.JsonDataStore;
import cz.vsb.minibank.infrastructure.json.dto.JsonFraudAlert;
import cz.vsb.minibank.infrastructure.json.mapping.JsonMapper;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class JsonFraudAlertRepository implements FraudAlertRepository {
    private final JsonDataStore store;
    public JsonFraudAlertRepository(JsonDataStore store) { this.store = store; }

    @Override public int nextId() {
        return store.data().fraudAlerts.stream().map(a -> a.id).max(Comparator.naturalOrder()).orElse(9000) + 1;
    }

    @Override public void add(FraudAlert a) {
        store.data().fraudAlerts.add(JsonMapper.toDto(a));
        try { store.save(); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Override public void save(FraudAlert a) {
        var list = store.data().fraudAlerts;
        var idx = -1; for (int i=0;i<list.size();i++) if (list.get(i).id == a.id()) { idx=i; break; }
        JsonFraudAlert dto = JsonMapper.toDto(a);
        if (idx>=0) list.set(idx, dto); else list.add(dto);
        try { store.save(); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Override public Optional<FraudAlert> byId(int id) {
        return store.data().fraudAlerts.stream().filter(a -> a.id == id).findFirst().map(JsonMapper::toDomain);
    }

    @Override public Optional<FraudAlert> byTransferId(int transferId) {
        return store.data().fraudAlerts.stream().filter(a -> a.transferId == transferId).findFirst().map(JsonMapper::toDomain);
    }

    @Override public List<FraudAlert> all() {
        return store.data().fraudAlerts.stream().map(JsonMapper::toDomain).collect(Collectors.toList());
    }
}