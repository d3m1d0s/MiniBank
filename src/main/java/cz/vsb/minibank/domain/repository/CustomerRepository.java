package cz.vsb.minibank.domain.repository;

import cz.vsb.minibank.domain.Beneficiary;
import cz.vsb.minibank.domain.Customer;

import java.util.Optional;

/**
 * Repository abstraction for customers and their beneficiaries.
 */
public interface CustomerRepository {

    /**
     * Returns the next technical identifier for a new customer.
     */
    int nextId();

    /**
     * Finds a customer by identifier.
     */
    Optional<Customer> byId(int id);

    /**
     * Finds the customer owning the given account.
     * Inverse of {@link AccountRepository#byCustomerId(int)}.
     */
    Optional<Customer> byAccountId(int accountId);

    /**
     * Inserts or updates the given customer aggregate.
     */
    void save(Customer c);

    /**
     * Returns the next identifier for a new beneficiary.
     */
    int nextBeneficiaryId();

    /**
     * Finds a beneficiary by its identifier.
     */
    Optional<Beneficiary> beneficiaryById(int beneficiaryId);

    /**
     * Inserts or updates a beneficiary and associates it with the given customer.
     */
    void saveBeneficiary(int customerId, Beneficiary b);
}
