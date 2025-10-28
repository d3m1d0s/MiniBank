package cz.vsb.minibank.infrastructure.json.repo;

import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.infrastructure.json.JsonDataStore;
import cz.vsb.minibank.infrastructure.json.dto.JsonAccount;
import cz.vsb.minibank.infrastructure.json.mapping.JsonMapper;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class JsonAccountRepository implements AccountRepository {
    private final JsonDataStore store;
    public JsonAccountRepository(JsonDataStore store) { this.store = store; }

    @Override public int nextId() {
        return store.data().accounts.stream().map(a -> a.id).max(Comparator.naturalOrder()).orElse(100) + 1;
    }

    @Override public Optional<Account> byId(int id) {
        return store.data().accounts.stream().filter(a -> a.id == id).findFirst().map(JsonMapper::toDomain);
    }

    @Override public Optional<Account> byIban(IBAN iban) {
        return store.data().accounts.stream().filter(a -> a.iban.equalsIgnoreCase(iban.value())).findFirst().map(JsonMapper::toDomain);
    }

    @Override public void save(Account account) {
        var list = store.data().accounts;
        var idx = -1; for (int i=0;i<list.size();i++) if (list.get(i).id == account.id()) { idx=i; break; }
        JsonAccount dto = JsonMapper.toDto(account);
        if (idx>=0) list.set(idx, dto); else list.add(dto);
        try { store.save(); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Override public List<Account> byCustomerId(int customerId) {
        var cust = store.data().customers.stream().filter(c -> c.id == customerId).findFirst();
        if (cust.isEmpty()) return List.of();
        var ids = cust.get().accountIds;
        return store.data().accounts.stream().filter(a -> ids.contains(a.id)).map(JsonMapper::toDomain).collect(Collectors.toList());
    }
}