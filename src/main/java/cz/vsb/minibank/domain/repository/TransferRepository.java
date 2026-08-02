package cz.vsb.minibank.domain.repository;

import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.value.Money;

import java.time.Instant;
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

    /**
     * Totals the CZK amounts that have actually left the given account in the half-open instant
     * range, fees excluded.
     *
     * "Actually left" is status SENT. {@link cz.vsb.minibank.domain.Transfer#send} is the only
     * method that assigns it, it is the only method that debits, and it is terminal, so no
     * auxiliary flag is needed. The range is matched against the transfer's creation time,
     * because that is the only timestamp a transfer carries - nothing records when one settled.
     *
     * An aggregate rather than a filter over {@link #bySourceAccount}: that method substitutes
     * instances from the identity map, whose in-memory status can already differ from the
     * stored one, and it constructs one Transfer per row to read one number off it. A row whose
     * creation time is missing or unreadable is left out, on both backends, and so is a row in
     * any currency but CZK.
     *
     * @return the total in CZK; both stores hold one currency and every transfer is created
     *         with it, and the currency predicate is what keeps that assumption checked
     */
    Money sentTotalBetween(int accountId, Instant fromInclusive, Instant toExclusive);
}
