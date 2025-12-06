package cz.vsb.minibank.domain.repository;

import cz.vsb.minibank.domain.Transfer;

import java.util.List;
import java.util.Optional;

/**
 * Repository abstraction for outgoing transfers.
 */
public interface TransferRepository {

    /**
     * Returns the next technical identifier for a new transfer.
     */
    int nextId();

    /**
     * Adds a new transfer.
     */
    void add(Transfer t);

    /**
     * Updates an existing transfer.
     */
    void save(Transfer t);

    /**
     * Finds a transfer by identifier.
     */
    Optional<Transfer> byId(int id);

    /**
     * Returns all transfers originating from the given account.
     */
    List<Transfer> bySourceAccount(int accountId);
}
