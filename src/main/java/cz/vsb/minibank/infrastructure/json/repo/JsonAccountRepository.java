package cz.vsb.minibank.infrastructure.json.repo;

import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.exceptions.DataIntegrityException;
import cz.vsb.minibank.domain.repository.AccountRepository;
import cz.vsb.minibank.domain.repository.CustomerRepository;
import cz.vsb.minibank.domain.value.IBAN;
import cz.vsb.minibank.infrastructure.json.JsonDataStore;
import cz.vsb.minibank.infrastructure.json.dto.JsonAccount;
import cz.vsb.minibank.infrastructure.json.mapping.JsonMapper;
import cz.vsb.minibank.infrastructure.uow.IdentityMapAccounts;
import cz.vsb.minibank.infrastructure.uow.UowContext;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * JSON-backed implementation of {@link AccountRepository}.
 * Uses {@link JsonDataStore} as the persistence backend and participates
 * in the UnitOfWork / Identity Map mechanism when a UnitOfWork is active.
 */
public class JsonAccountRepository implements AccountRepository {
    private final JsonDataStore store;

    public JsonAccountRepository(JsonDataStore store) {
        this.store = store;
    }

    @Override
    public int nextId() {
        return store.nextAccountId();
    }

    @Override
    public Optional<Account> byId(int id) {
        UnitOfWork uow = UowContext.current();
        if (uow != null) {
            Account cached = uow.get(Account.class, id);
            if (cached != null) {
                return Optional.of(cached);
            }
        }
        // Mapping happens inside the read: toDomain reads several fields off the DTO, and a
        // concurrent commit replacing that DTO between two of them would build a torn Account.
        return store.read(bundle -> {
            var f = bundle.accounts.stream().filter(a -> a.id == id).findFirst();
            if (f.isEmpty()) {
                return Optional.<Account>empty();
            }
            Account d = JsonMapper.toDomain(f.get());
            if (uow != null) {
                uow.put(Account.class, d.id(), d);
            }
            return Optional.of(d);
        });
    }

    @Override
    public Optional<Account> byIban(IBAN iban) {
        UnitOfWork uow = UowContext.current();

        // What this transaction already holds comes first, including accounts it has created
        // whose rows are still buffered and therefore invisible to the scan below.
        Optional<Account> inFlight = IdentityMapAccounts.byIban(uow, iban);
        if (inFlight.isPresent()) {
            return inFlight;
        }

        return store.read(bundle -> {
            // Exact, where this used to fold case, and that is the correction rather than the
            // regression it looks like. SqlAccountRepository binds this same already-normalized
            // iban.value() to WHERE iban = ?, and no constraint normalizes accounts.iban there,
            // so a row stored in lower case belongs to no account on that backend. Settlement
            // routes on this answer - found is credited here, absent is handed to the external
            // gateway - so the two backends disagreeing about one row is one dataset sending the
            // same money to two different places.
            //
            // Normalizing the stored value at read time would close the gap the other way, but it
            // needs a second change on the SQL side and it accepts a row the application cannot
            // write: every stored IBAN comes from JsonMapper.toDto, which writes iban().value().
            // The query side loses nothing either way, because IBAN's constructor has already
            // normalized what arrives here. iban.value() is the receiver so that a hand-written
            // row with no iban key belongs to no account rather than to a NullPointerException,
            // which is what a NULL column does in the SQL predicate too.
            var matches = bundle.accounts.stream()
                    .filter(a -> iban.value().equals(a.iban))
                    .toList();
            if (matches.isEmpty()) {
                return Optional.<Account>empty();
            }
            // accounts.iban is UNIQUE in the SQL schema and this line is the whole of that
            // constraint on the JSON side. Taking the first of several was harmless while the
            // answer only decided whether the demo was already seeded; it now decides who
            // receives money, so an ambiguous store is refused rather than resolved by
            // whichever row happens to come first.
            if (matches.size() > 1) {
                throw new DataIntegrityException("IBAN " + iban.value() + " is held by "
                        + matches.size() + " accounts: "
                        + matches.stream().map(a -> String.valueOf(a.id)).toList());
            }
            JsonAccount row = matches.get(0);
            // The row identifies the account, the unit of work owns the instance. The probe
            // cannot come before the query, because the row is what supplies the id. Without
            // it this lookup builds a second Account for a row the transaction may already
            // hold, and the put below makes that second instance the one every later byId
            // returns. byId, byCustomerId and both JsonMapper lazy loaders already probe;
            // this was the only lookup on either backend that did not.
            if (uow != null) {
                Account cached = uow.get(Account.class, row.id);
                if (cached != null) {
                    return Optional.of(cached);
                }
            }
            Account d = JsonMapper.toDomain(row);
            if (uow != null) {
                uow.put(Account.class, d.id(), d);
            }
            return Optional.of(d);
        });
    }

    @Override
    public void save(Account account) {
        UnitOfWork uow = UowContext.current();
        Runnable mutate = () -> {
            var list = store.data().accounts;
            int idx = -1;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id == account.id()) {
                    idx = i;
                    break;
                }
            }
            JsonAccount dto = JsonMapper.toDto(account);
            if (idx >= 0) {
                list.set(idx, dto);
            } else {
                list.add(dto);
            }
        };
        if (uow != null) {
            uow.registerMutation(mutate);
            uow.put(Account.class, account.id(), account);
        } else {
            // No unit of work: the mutation and the persist are one lock hold, so no
            // other thread can save a half-applied list.
            store.mutateAndSave(mutate);
        }
    }

    @Override
    public List<Account> byCustomerId(int customerId) {
        UnitOfWork uow = UowContext.current();

        // One hold for all three reads: the customer lookup, its nested accountIds and
        // the accounts scan must see the same state. accountIds is read once per element
        // of the outer stream, so it must not escape the lock.
        return store.read(bundle -> {
            var cust = bundle.customers.stream().filter(c -> c.id == customerId).findFirst();
            if (cust.isEmpty()) {
                return List.<Account>of();
            }
            var ids = cust.get().accountIds;

            return bundle.accounts.stream()
                    .filter(a -> ids.contains(a.id))
                    .map(dto -> {
                        if (uow != null) {
                            Account cached = uow.get(Account.class, dto.id);
                            if (cached != null) {
                                return cached;
                            }
                        }
                        Account d = JsonMapper.toDomain(dto);
                        if (uow != null) {
                            uow.put(Account.class, d.id(), d);
                        }
                        return d;
                    })
                    .collect(Collectors.toList());
        });
    }

}
