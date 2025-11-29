package cz.vsb.minibank.domain.repository;


import cz.vsb.minibank.domain.Beneficiary;
import cz.vsb.minibank.domain.Customer;


import java.util.Optional;


public interface CustomerRepository {
    int nextId();
    Optional<Customer> byId(int id);
    void save(Customer c); // insert/update


    int nextBeneficiaryId();
    Optional<Beneficiary> beneficiaryById(int beneficiaryId);
    void saveBeneficiary(int customerId, Beneficiary b); // insert/update (also attach to the customer)
}