package cz.vsb.minibank.domain.repository;

import cz.vsb.minibank.domain.customer.Beneficiary;
import cz.vsb.minibank.domain.customer.Customer;

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
     * Inserts or updates a beneficiary and associates it with the given customer.
     *
     * There is deliberately no beneficiaryById(int). It took a caller-supplied id and
     * returned an object that cannot report its owner, so no caller could make it safe.
     * Beneficiaries are resolved from {@link Customer#beneficiaries()}, which both backends
     * already load scoped to the customer. A per-beneficiary read, if one is ever needed,
     * takes the customer id as its first parameter.
     */
    void saveBeneficiary(int customerId, Beneficiary b);
}
