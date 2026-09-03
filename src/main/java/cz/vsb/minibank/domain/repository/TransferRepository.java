package cz.vsb.minibank.domain.repository;

import cz.vsb.minibank.domain.transfer.Transfer;
import cz.vsb.minibank.domain.transfer.TransferStatus;
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
     * One page of the transfers sent from a set of accounts, newest first.
     *
     * The set is the accounts one CUSTOMER holds, the same shape and for the same reason
     * {@link #sentTotalToIbanBetween} takes: a customer's payment history is one list across their
     * accounts, and asking per account and merging in Java would order the result by account and
     * then by date, which is not an order anybody asked for.
     *
     * WHY IT IS PAGED AT ALL. {@link #bySourceAccount} answers with every row an account has ever
     * sent and every caller then took what it wanted from that, which is a query whose cost grows
     * with the customer's history to draw one screenful. The bound belongs in the store, so the
     * page is what is read rather than what is kept.
     *
     * NEWEST FIRST, AND TOTALLY ORDERED. created_at descending with the id descending behind it.
     * The tie-break is not decoration: two payments created in the same instant, or with the same
     * stored timestamp, leave the sort undecided, and an undecided sort under offset paging shows
     * one row twice on page two and drops another entirely. bySourceAccount's ascending id order
     * is left alone - the sweep and the waiting screen it serves want it.
     *
     * @param accountIds the accounts to read from, in any order; an empty collection answers with
     *                   an empty list without touching the store
     * @param statuses   the statuses to include; AN EMPTY COLLECTION MEANS EVERY STATUS, which is
     *                   what the customer's own history asks for. The waiting list passes the two
     *                   it can act on, so both lists page through one query rather than through a
     *                   query and a filter applied afterwards - a filter applied after the page
     *                   would answer a page of three rows and call it a page of twenty-five
     * @param offset     how many rows to skip; zero or more
     * @param limit      how many rows to return; zero or fewer answers with an empty list
     */
    List<Transfer> bySourceAccountsNewestFirst(Collection<Integer> accountIds,
                                               Collection<TransferStatus> statuses,
                                               int offset,
                                               int limit);

    /**
     * How many rows the page above is taken out of.
     *
     * Counted in the store rather than by measuring a list this application has loaded, which is
     * the whole point of the pair: the foot of a list reads "Showing 25 of 137" and the second
     * number cannot come from the twenty-five rows on screen. The predicate is the page query's,
     * to the letter, so the two can never describe different sets.
     *
     * @param accountIds as above; empty counts zero without touching the store
     * @param statuses   as above; empty counts every status
     */
    int countBySourceAccounts(Collection<Integer> accountIds,
                              Collection<TransferStatus> statuses);

    /**
     * Every transfer that has settled out of this bank and that no gateway has been handed yet,
     * in ascending id order.
     *
     * The dispatch state alone decides membership, and the status deliberately takes no part in
     * it. {@link cz.vsb.minibank.domain.transfer.Transfer#send} is the only writer of a pending state and
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
     * Totals the CZK amounts that have actually left the given CUSTOMER in the half-open instant
     * range, fees excluded: sent out of any of these accounts, less what only moved to another
     * of them.
     *
     * WHY THE EXCLUSION. This total is what the daily ceiling is measured against, and the
     * ceiling is now a limit on a person - see {@link cz.vsb.minibank.domain.customer.Customer#dailyLimit}.
     * Summing a customer's accounts without subtracting their internal moves would count one such
     * move twice, once as an outflow of the paying account and again as nothing at all on the
     * receiving side, so a customer could raise their own day total, or exhaust it, by shuffling
     * money between their own accounts without a heller leaving the bank on their behalf.
     *
     * WHY IT IS KEYED ON THE DESTINATION IBAN AND NOT ON "STAYED IN THIS BANK". Those are
     * different sets. A trusted payee may bank here too, and a payment to them has left the
     * customer as surely as one that goes out over the network. The predicate is membership of
     * the destination snapshot in the customer's own IBANs, and nothing wider.
     *
     * "Actually left" is status SENT. {@link cz.vsb.minibank.domain.transfer.Transfer#send} is the only
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
     * The stored snapshot is compared in its normalized form, for the reason {@link
     * #sentTotalToIbanBetween} gives: {@code Transfer} takes it as a plain String and a
     * denormalized one is reachable through the public constructor. A row whose snapshot is
     * missing matches no exclusion and is therefore counted, which is the safe way round: the
     * column is NOT NULL in SQL only, and a row the JSON store cannot say the destination of is
     * not a row this bank may treat as an internal move.
     *
     * @param accountIds the accounts to total across, in any order; a repeated id changes
     *                   nothing, because this is a membership test and not a join, and an empty
     *                   set totals to zero without touching the store
     * @param ownIbans   the destinations that do not count as leaving, in any form; normalized
     *                   before comparison. In production this is the IBANs of exactly those
     *                   accounts, and an empty collection excludes nothing, which is what makes
     *                   the single-account form below a call to this one
     * @return the total in CZK; both stores hold one currency and every transfer is created
     *         with it. The currency predicate was once the only thing keeping that assumption
     *         checked; it is now the last of three, behind Transfer's constructor and the
     *         transfers_currency_czk constraint, and is kept because this aggregate reads rows
     *         without building a Transfer out of any of them
     */
    Money sentTotalLeavingCustomerBetween(Collection<Integer> accountIds,
                                          Collection<String> ownIbans,
                                          Instant fromInclusive,
                                          Instant toExclusive);

    /**
     * The plain total for one account, excluding nothing.
     *
     * Not the form the ceiling asks for - that one is above and spans a customer - but the form
     * the per-backend tests state the row-level rules in, where there is no customer to hang them
     * on: which rows count, which timestamp decides the window, what a malformed row does. A
     * default rather than a query of its own on each backend, so that the rules those tests pin
     * are the rules the ceiling is actually measured by.
     */
    default Money sentTotalBetween(int accountId, Instant fromInclusive, Instant toExclusive) {
        return sentTotalLeavingCustomerBetween(List.of(accountId), List.of(),
                fromInclusive, toExclusive);
    }

    /**
     * The same total over the same set of accounts, narrowed to one destination: what has left
     * any of these accounts for this IBAN in the range.
     *
     * A sibling rather than a parameter on the method above, because the two answer different
     * questions. That one bounds what a customer may spend in a day and the suite pins its shape;
     * this one feeds the fraud rule, which asks whether an amount is being split across several
     * payments to one new payee. Sharing an aggregate would mean a change made for the alert
     * could move the ceiling.
     *
     * The set the caller passes is the accounts one CUSTOMER holds, and saying so is the whole
     * of what this parameter is for. Keyed on the paying account alone - which is what it
     * inherited from the day total above, never argued for - the rule was optional for anybody
     * with a second account: 6 500 from each of two of their own accounts to one untrusted IBAN
     * is 13 000 in a day that no evaluation ever saw more than half of. The day total has since
     * been widened the same way, so the two now read the same rows and differ only in what they
     * ask of the destination: this one keeps a single named payee, that one drops the customer's
     * own accounts.
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
