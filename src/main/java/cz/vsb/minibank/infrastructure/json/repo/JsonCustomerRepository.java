package cz.vsb.minibank.infrastructure.json.repo;

import cz.vsb.minibank.domain.Beneficiary;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.repository.CustomerRepository;
import cz.vsb.minibank.domain.repository.TransferRepository;
import cz.vsb.minibank.infrastructure.json.JsonDataStore;
import cz.vsb.minibank.infrastructure.json.dto.JsonBeneficiary;
import cz.vsb.minibank.infrastructure.json.dto.JsonCustomer;
import cz.vsb.minibank.infrastructure.json.mapping.JsonMapper;
import cz.vsb.minibank.infrastructure.uow.UowContext;
import cz.vsb.minibank.infrastructure.uow.UnitOfWork;

import java.util.Optional;

/**
 * JSON-backed implementation of {@link CustomerRepository}.
 * Uses {@link JsonDataStore} as the persistence backend and participates
 * in the UnitOfWork / Identity Map mechanism when a UnitOfWork is active.
 */
public class JsonCustomerRepository implements CustomerRepository {
    private final JsonDataStore store;

    public JsonCustomerRepository(JsonDataStore store) {
        this.store = store;
    }

    @Override
    public int nextId() {
        return store.nextCustomerId();
    }

    @Override
    public Optional<Customer> byId(int id) {
        UnitOfWork uow = UowContext.current();
        if (uow != null) {
            Customer cached = uow.get(Customer.class, id);
            if (cached != null) {
                return Optional.of(cached);
            }
        }
        return store.read(bundle -> {
            var f = bundle.customers.stream().filter(c -> c.id == id).findFirst();
            if (f.isEmpty()) {
                return Optional.<Customer>empty();
            }
            // toDomain also walks the nested accountIds and beneficiaries lists, and the
            // LazyList it attaches captures this DTO - that closure locks for itself.
            Customer d = JsonMapper.toDomain(f.get(), store);
            if (uow != null) {
                uow.put(Customer.class, d.id(), d);
            }
            return Optional.of(d);
        });
    }

    @Override
    public Optional<Customer> byAccountId(int accountId) {
        // byId re-enters the store lock, so the search and the load are one hold and
        // cannot straddle another thread's commit.
        return store.read(bundle -> bundle.customers.stream()
                .filter(c -> c.accountIds != null && c.accountIds.contains(accountId))
                .findFirst()
                .flatMap(c -> byId(c.id)));
    }

    @Override
    public void save(Customer c) {
        UnitOfWork uow = UowContext.current();
        Runnable mutate = () -> {
            var list = store.data().customers;
            int idx = -1;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id == c.id()) {
                    idx = i;
                    break;
                }
            }
            JsonCustomer dto = JsonMapper.toDto(c);
            if (idx >= 0) {
                list.set(idx, dto);
            } else {
                list.add(dto);
            }
        };
        if (uow != null) {
            uow.registerMutation(mutate);
            uow.put(Customer.class, c.id(), c);
        } else {
            store.mutateAndSave(mutate);
        }
    }

    @Override
    public int nextBeneficiaryId() {
        return store.nextBeneficiaryId();
    }

    @Override
    public Optional<Beneficiary> beneficiaryById(int beneficiaryId) {
        // The nested beneficiaries lists this flatMap walks are the ones saveBeneficiary
        // mutates in place, which is the one structural modification of a nested list in
        // the whole adapter.
        return store.read(bundle -> {
            var f = bundle.customers.stream()
                    .flatMap(c -> c.beneficiaries.stream())
                    .filter(b -> b.id == beneficiaryId)
                    .findFirst();
            if (f.isEmpty()) {
                return Optional.<Beneficiary>empty();
            }
            // do not cache beneficiary globally in the Identity Map - it is aggregated inside Customer
            return Optional.of(JsonMapper.toDomain(f.get()));
        });
    }

    @Override
    public void saveBeneficiary(int customerId, Beneficiary b) {
        UnitOfWork uow = UowContext.current();
        Runnable mutate = () -> {
            JsonCustomer c = store.data().customers.stream()
                    .filter(x -> x.id == customerId)
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Customer not found"));
            int idx = -1;
            for (int i = 0; i < c.beneficiaries.size(); i++) {
                if (c.beneficiaries.get(i).id == b.id()) {
                    idx = i;
                    break;
                }
            }
            JsonBeneficiary jb = JsonMapper.toDto(b);
            if (idx >= 0) {
                c.beneficiaries.set(idx, jb);
            } else {
                c.beneficiaries.add(jb);
            }
        };

        if (uow != null) {
            uow.registerMutation(mutate);
            // update aggregate in Identity Map if already loaded in this UnitOfWork
            Customer cached = uow.get(Customer.class, customerId);
            if (cached != null) {
                cached.upsertBeneficiary(b);
            }
        } else {
            // The mutation scans the customers list and structurally modifies a nested
            // beneficiaries list; that plus the persist must be one hold.
            store.mutateAndSave(mutate);
        }
    }

}
