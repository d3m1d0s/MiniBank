package cz.vsb.minibank.infrastructure.json.repo;

import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.infrastructure.json.JsonDataStore;
import cz.vsb.minibank.infrastructure.json.dto.JsonAccount;
import cz.vsb.minibank.infrastructure.json.mapping.JsonMapper;
import cz.vsb.minibank.infrastructure.uow.UowContext;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class JsonAccountRepository implements AccountRepository {
    private final JsonDataStore store;
    public JsonAccountRepository(JsonDataStore store) { this.store = store; }

    @Override public int nextId() {
        return store.nextAccountId();
    }

    @Override public Optional<Account> byId(int id) {
        UnitOfWork uow = UowContext.current();
        if (uow != null) {
            Account cached = uow.get(Account.class, id);
            if (cached != null) return Optional.of(cached);
        }
        var f = store.data().accounts.stream().filter(a -> a.id == id).findFirst();
        if (f.isEmpty()) return Optional.empty();
        Account d = JsonMapper.toDomain(f.get());
        if (uow != null) uow.put(Account.class, d.id(), d);
        return Optional.of(d);
    }

    @Override public Optional<Account> byIban(IBAN iban) {
        UnitOfWork uow = UowContext.current();
        var f = store.data().accounts.stream().filter(a -> a.iban.equalsIgnoreCase(iban.value())).findFirst();
        if (f.isEmpty()) return Optional.empty();
        Account d = JsonMapper.toDomain(f.get());
        if (uow != null) uow.put(Account.class, d.id(), d);
        return Optional.of(d);
    }

    @Override public void save(Account account) {
        UnitOfWork uow = UowContext.current();
        Runnable mutate = () -> {
            var list = store.data().accounts;
            int idx = -1; for (int i=0;i<list.size();i++) if (list.get(i).id == account.id()) { idx=i; break; }
            JsonAccount dto = JsonMapper.toDto(account);
            if (idx>=0) list.set(idx, dto); else list.add(dto);
        };
        if (uow != null) {
            uow.registerMutation(mutate);
            uow.put(Account.class, account.id(), account);
        } else {
            mutate.run();
            try { store.save(); } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Override
    public List<Account> byCustomerId(int customerId) {
        UnitOfWork uow = UowContext.current();

        var cust = store.data().customers.stream().filter(c -> c.id == customerId).findFirst();
        if (cust.isEmpty()) return List.of();
        var ids = cust.get().accountIds;

        return store.data().accounts.stream()
                .filter(a -> ids.contains(a.id))
                .map(dto -> {
                    if (uow != null) {
                        Account cached = uow.get(Account.class, dto.id);
                        if (cached != null) return cached;
                    }
                    Account d = JsonMapper.toDomain(dto);
                    if (uow != null) uow.put(Account.class, d.id(), d);
                    return d;
                })
                .collect(Collectors.toList());
    }

}