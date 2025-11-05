package cz.vsb.minibank.infrastructure.json.repo;

import cz.vsb.minibank.domain.Beneficiary;
import cz.vsb.minibank.domain.Customer;
import cz.vsb.minibank.domain.repository.CustomerRepository;
import cz.vsb.minibank.infrastructure.json.JsonDataStore;
import cz.vsb.minibank.infrastructure.json.dto.JsonBeneficiary;
import cz.vsb.minibank.infrastructure.json.dto.JsonCustomer;
import cz.vsb.minibank.infrastructure.json.mapping.JsonMapper;

import java.util.Comparator;
import java.util.Optional;

public class JsonCustomerRepository implements CustomerRepository {
    private final JsonDataStore store;
    public JsonCustomerRepository(JsonDataStore store) { this.store = store; }

    @Override public int nextId() {
        return store.nextCustomerId();
    }

    @Override public Optional<Customer> byId(int id) {
        return store.data().customers.stream().filter(c -> c.id == id).findFirst().map(JsonMapper::toDomain);
    }

    @Override public void save(Customer c) {
        var list = store.data().customers;
        var idx = -1; for (int i=0;i<list.size();i++) if (list.get(i).id == c.id()) { idx=i; break; }
        JsonCustomer dto = JsonMapper.toDto(c);
        if (idx>=0) list.set(idx, dto); else list.add(dto);
        try { store.save(); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Override public int nextBeneficiaryId() {
        return store.nextBeneficiaryId();
    }

    @Override public Optional<Beneficiary> beneficiaryById(int beneficiaryId) {
        return store.data().customers.stream().flatMap(c -> c.beneficiaries.stream())
                .filter(b -> b.id == beneficiaryId).findFirst().map(JsonMapper::toDomain);
    }

    @Override public void saveBeneficiary(int customerId, Beneficiary b) {
        JsonCustomer c = store.data().customers.stream().filter(x -> x.id == customerId).findFirst()
                .orElseThrow(() -> new RuntimeException("Customer not found"));
        var idx = -1; for (int i=0;i<c.beneficiaries.size();i++) if (c.beneficiaries.get(i).id == b.id()) { idx=i; break; }
        JsonBeneficiary jb = JsonMapper.toDto(b);
        if (idx>=0) c.beneficiaries.set(idx, jb); else c.beneficiaries.add(jb);
        try { store.save(); } catch (Exception e) { throw new RuntimeException(e); }
    }
}