package cz.vsb.minibank.domain.repository;

import cz.vsb.minibank.domain.Transfer;
import cz.vsb.minibank.domain.value.Money;

import java.time.Instant;
import java.util.Collection;
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
     * Every transfer that has settled out of this bank and that no gateway has been handed yet,
     * in ascending id order.
     *
     * The dispatch state alone decides membership, and the status deliberately takes no part in
     * it. {@link cz.vsb.minibank.domain.Transfer#send} is the only writer of a pending state and
     * it assigns SENT in the same call, so a status predicate would say nothing this one does not
     * - and would silently narrow the answer the day the two disagreed, which on this query means
     * a payment nobody ever sends.
     *
     * Ordered, like {@link #bySourceAccount} and for a reason of the same kind: a sweep that fails
     * partway through retries the same payments in the same order rather than in whatever order
     * the store's pages happen to hold them after a row has been rewritten.
     *
     * Not an aggregate and not a projection: the caller dispatches these transfers and then marks
     * them, so it needs the aggregates themselves, served from the identity map when the current
     * unit of work already holds them.
     */
    List<Transfer> awaitingDispatch();

    /**
     * Totals the CZK amounts that have actually left the given account in the half-open instant
     * range, fees excluded.
     *
     * "Actually left" is status SENT. {@link cz.vsb.minibank.domain.Transfer#send} is the only
     * method that assigns it on the money path, it is the only method that debits, and it is
     * terminal, so no auxiliary flag is needed.
     *
     * The range is matched against {@code COALESCE(settled_at, created_at)}: when the money
     * moved, falling back to when the order was placed for rows written before {@code settled_at}
     * existed. This paragraph used to say the range was matched against the creation time
     * "because that is the only timestamp a transfer carries - nothing records when one settled",
     * which stopped being true when settled_at was added and both implementations moved to the
     * fallback. A transfer an analyst held for a week is counted against the day it settles on,
     * not the day it was ordered on.
     *
     * An aggregate rather than a filter over {@link #bySourceAccount}: that method substitutes
     * instances from the identity map, whose in-memory status can already differ from the
     * stored one, and it constructs one Transfer per row to read one number off it. A row whose
     * creation time is missing or unreadable is left out, on both backends, and so is a row in
     * any currency but CZK.
     *
     * @return the total in CZK; both stores hold one currency and every transfer is created
     *         with it. The currency predicate was once the only thing keeping that assumption
     *         checked; it is now the last of three, behind Transfer's constructor and the
     *         transfers_currency_czk constraint, and is kept because this aggregate reads rows
     *         without building a Transfer out of any of them
     */
    Money sentTotalBetween(int accountId, Instant fromInclusive, Instant toExclusive);

    /**
     * The same total, narrowed to one destination and widened to a set of accounts: what has
     * left any of these accounts for this IBAN in the range.
     *
     * A sibling rather than a parameter on the method above, because the two answer different
     * questions and only one of them may ever widen. That one bounds what a customer may spend
     * in a day and the suite pins its shape; this one feeds the fraud rule, which asks whether
     * an amount is being split across several payments to one new payee. Sharing an aggregate
     * would mean a change made for the alert could move the ceiling.
     *
     * The set the caller passes is the accounts one CUSTOMER holds, and saying so is the whole
     * of what this parameter is for. Keyed on the paying account alone - which is what it
     * inherited from the day total above, never argued for - the rule was optional for anybody
     * with a second account: 6 500 from each of two of their own accounts to one untrusted IBAN
     * is 13 000 in a day that no evaluation ever saw more than half of. The day total keeps the
     * narrower scope deliberately, because the ceiling it is compared against is a column on one
     * account and a total over another account's rows could not be measured against it.
     *
     * The stored snapshot is compared in its normalized form - see {@link
     * cz.vsb.minibank.domain.value.IBAN#normalize} - because {@code Transfer} takes the snapshot
     * as a plain String and a denormalized one is reachable through the public constructor. A
     * row whose snapshot is missing is left out rather than matched: the column is NOT NULL in
     * SQL only, and the JSON store has no such guarantee.
     *
     * @param accountIds the accounts to total across, in any order; a repeated id changes
     *                   nothing, because this is a membership test and not a join, and an empty
     *                   set totals to zero
     * @param targetIban the destination, in any form; normalized before comparison
     * @return the total in CZK, on the same terms as {@link #sentTotalBetween}
     */
    Money sentTotalToIbanBetween(Collection<Integer> accountIds,
                                 String targetIban,
                                 Instant fromInclusive,
                                 Instant toExclusive);

    /**
     * The same total over a single account.
     *
     * Not the form the fraud rule asks for - that one is above and spans a customer - but the
     * form a caller holding one account id means, and the one the per-backend tests state the
     * row-level rules in, where there is no customer to hang them on. A default rather than a
     * second query on each backend, so the two shapes cannot come to disagree.
     */
    default Money sentTotalToIbanBetween(int accountId,
                                         String targetIban,
                                         Instant fromInclusive,
                                         Instant toExclusive) {
        return sentTotalToIbanBetween(List.of(accountId), targetIban, fromInclusive, toExclusive);
    }
}
