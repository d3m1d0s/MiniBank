package cz.vsb.minibank.domain.repository;

import cz.vsb.minibank.domain.Account;
import cz.vsb.minibank.domain.value.IBAN;

import java.util.List;
import java.util.Optional;

/**
 * Repository abstraction for accessing and persisting accounts.
 */
public interface AccountRepository {

    /**
     * Returns the next technical identifier for a new account.
     */
    int nextId();

    /**
     * Finds an account by its identifier.
     */
    Optional<Account> byId(int id);

    /**
     * Finds an account by its IBAN.
     */
    Optional<Account> byIban(IBAN iban);

    /**
     * Inserts or updates the given account.
     */
    void save(Account account);

    /**
     * Returns all accounts belonging to the given customer.
     */
    List<Account> byCustomerId(int customerId);
}
