package cz.vsb.minibank.domain.repository;

import cz.vsb.minibank.domain.customer.Account;
import cz.vsb.minibank.domain.exceptions.InvalidIbanException;
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
     * Finds the account holding this IBAN when it belongs to this bank.
     *
     * One definition of the in-bank question for every settle site, so the routing rule cannot
     * drift between TransferApplicationService, FraudApplicationService and DemoScenario.
     * Takes the raw snapshot a transfer carries rather than an {@link IBAN}, because that is
     * what the callers hold. A snapshot that no longer parses belongs to no account here:
     * every stored IBAN is written from a validated value object, so a row that could match it
     * could not itself be loaded.
     */
    default Optional<Account> inBankByIban(String rawIban) {
        try {
            return byIban(new IBAN(rawIban));
        } catch (InvalidIbanException e) {
            return Optional.empty();
        }
    }

    /**
     * Inserts or updates the given account.
     */
    void save(Account account);

    /**
     * Saves the two accounts of one settlement, always the lower id first.
     *
     * The order is not cosmetic. Both backends defer their writes to commit and run them in
     * registration order, and the SQL upsert takes an exclusive row lock that it holds until
     * the transaction commits. A credit leg is the first thing in this codebase that writes
     * two account rows in one transaction, so two settlements naming the same pair in opposite
     * directions - A pays B while B pays A - would take those two locks in opposite orders and
     * deadlock. Ordering every such pair by id makes that impossible, and it is why both
     * services hand their pair to this one method instead of calling {@link #save} twice.
     *
     * @param second null for a payment that leaves the bank, which writes one row
     */
    default void saveBothInIdOrder(Account first, Account second) {
        if (second == null) {
            save(first);
            return;
        }
        if (first.id() <= second.id()) {
            save(first);
            save(second);
        } else {
            save(second);
            save(first);
        }
    }

    /**
     * Returns all accounts belonging to the given customer.
     */
    List<Account> byCustomerId(int customerId);
}
