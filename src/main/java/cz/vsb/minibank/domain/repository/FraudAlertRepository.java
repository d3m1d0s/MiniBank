package cz.vsb.minibank.domain.repository;

import cz.vsb.minibank.domain.FraudAlert;
import cz.vsb.minibank.domain.FraudAlertNote;
import cz.vsb.minibank.domain.FraudAlertState;
import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.value.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Repository abstraction for fraud alerts.
 */
public interface FraudAlertRepository {

    /**
     * Returns the next technical identifier for a new fraud alert.
     */
    int nextId();

    /**
     * Adds a new fraud alert.
     */
    void add(FraudAlert a);

    /**
     * Updates an existing fraud alert.
     */
    void save(FraudAlert a);

    /**
     * Finds a fraud alert by identifier.
     */
    Optional<FraudAlert> byId(int id);

    /**
     * Finds a fraud alert by associated transfer identifier.
     */
    Optional<FraudAlert> byTransferId(int transferId);

    /**
     * Returns all fraud alerts.
     */
    List<FraudAlert> all();

    // -------------------------------------------------------------------------
    // The notes journal
    // -------------------------------------------------------------------------

    /**
     * Adds one entry to an alert's journal. Nothing else writes it, and nothing anywhere edits or
     * removes an entry.
     *
     * A METHOD OF ITS OWN RATHER THAN A FIELD ON THE AGGREGATE, and the reason is what the journal
     * has to survive. Carried on {@link FraudAlert} it would travel through {@code save}, which on
     * both backends rewrites the whole record: on JSON that means replacing the stored row with a
     * fresh copy built from the aggregate, so any entry the aggregate had not loaded would be
     * erased by an unrelated assignment. Appending through its own insert cannot lose an entry,
     * however out of date the caller's copy of the alert is, and it is also what keeps the
     * append-only rule a property of the store instead of a convention callers observe.
     *
     * It carries no version and takes part in no optimistic-lock check, deliberately. Two analysts
     * appending at the same moment are not in conflict: both notes are facts about the case and
     * both belong in it. The guard on the alert row exists because a verdict overwrites a verdict,
     * and nothing here overwrites anything.
     *
     * @param note the entry, already complete: the alert, the author, the instant and the text
     */
    void appendNote(FraudAlertNote note);

    /**
     * One alert's journal, oldest entry first.
     *
     * Oldest first because a journal is read the way it was written, and because the entry the
     * migration carried over from the old single {@code notes} column is dated at the alert's own
     * creation instant and therefore stands where it belongs, at the top.
     *
     * READ ON ITS OWN AND NEVER WITH THE QUEUE. The queue prints one line per alert and has no
     * room for a journal; loading one per row would put a second statement behind every row of a
     * page, which is the N+1 the queue was rebuilt to remove. Only the alert detail asks for this.
     *
     * An alert that has no journal, and an id no alert has, both answer with an empty list: a
     * missing alert is the detail route's question, not this one's.
     */
    List<FraudAlertNote> notesOf(int alertId);

    // -------------------------------------------------------------------------
    // The analyst queue
    // -------------------------------------------------------------------------

    /**
     * Everything that narrows the analyst queue, as one value rather than as seven parameters.
     *
     * WHY THE FILTERS ARE DOWN HERE AT ALL. The queue used to read every alert ever raised, fetch
     * the payment behind each one in a statement of its own, and then drop the rows that did not
     * match. That cannot be paged: a page taken before the filters runs short, and one taken after
     * them has already paid for the whole store. Three of these narrow the alert and three narrow
     * the payment behind it, so a store that wants to answer with a page has to be told all six.
     *
     * WHAT IS NOT IN HERE, AND MUST NOT BE. The counters. They describe the whole queue before any
     * of this is applied, which is why {@link #countByState()} takes no filter at all: a strip
     * reading "New: 1" over a queue holding forty is worse than no strip.
     *
     * @param state                     the alert state to keep, or null to keep every state
     * @param createdFrom               the earliest alert creation instant to keep, inclusive
     * @param createdTo                 the latest, also inclusive; either may be null
     * @param assigneeContains          a fragment the assignee must contain, matched without
     *                                  regard to case; null or blank keeps every alert, including
     *                                  the unassigned ones
     * @param minAmount                 the smallest payment amount to keep, inclusive
     * @param maxAmount                 the largest, also inclusive; either may be null
     * @param excludedTransferStatuses  payment statuses whose alerts are hidden. An exclusion
     *                                  rather than an inclusion, and {@code FraudController}
     *                                  explains why; empty hides nothing
     */
    record QueueFilter(FraudAlertState state,
                       Instant createdFrom,
                       Instant createdTo,
                       String assigneeContains,
                       BigDecimal minAmount,
                       BigDecimal maxAmount,
                       Set<TransferStatus> excludedTransferStatuses) {

        public QueueFilter {
            excludedTransferStatuses = (excludedTransferStatuses == null)
                    ? Set.of()
                    : Set.copyOf(excludedTransferStatuses);
        }

        /** The filter that narrows nothing: every alert that still has a payment behind it. */
        public static QueueFilter none() {
            return new QueueFilter(null, null, null, null, null, null, Set.of());
        }
    }

    /**
     * One row of the queue: the alert, and the facts of the payment behind it that the queue prints.
     *
     * The payment arrives as three plain values rather than as a {@code Transfer}, because the
     * point of this shape is that the store answers with one statement instead of one per row. A
     * page of aggregates would mean loading each payment in full to read its status and its
     * amount off it, which is the query being killed. It follows the precedent
     * {@code TransferRepository.sentTotalBetween} already sets: a read that needs numbers off rows
     * takes the numbers, not the aggregates.
     *
     * The alert itself is a real aggregate, served from the identity map when the current unit of
     * work already holds it, because the queue's own filters are stated in its terms and a decision
     * taken in this transaction has to be the one the queue shows.
     */
    record QueueRow(FraudAlert alert, int transferId, TransferStatus transferStatus, Money amount) {
    }

    /**
     * One page of the queue, newest alert first, matching the filter.
     *
     * Ordered by creation instant descending and then by id descending. The tie break is not
     * decoration: without a total order, two alerts raised in the same instant can swap places
     * between two requests, which on offset paging repeats one row on the next page and drops
     * another entirely.
     *
     * An alert whose payment is missing is left out, exactly as the controller's loop left it out
     * before, and on the SQL side that is what the join does on its own.
     *
     * @param offset how many matching rows to skip; never negative
     * @param limit  how many to return; a limit of zero returns nothing
     */
    List<QueueRow> queuePage(QueueFilter filter, int offset, int limit);

    /**
     * How many rows the filter matches in total, which is what the foot of the list counts against.
     * Counted rather than derived from a page, so the number does not depend on how far the analyst
     * has scrolled.
     */
    int queueTotal(QueueFilter filter);

    /**
     * How many alerts are in each state, over the whole queue and before any filter or page.
     *
     * The counters an analyst watches, and the reason this takes no argument. They are deliberately
     * not the page's total and must never be derived from it: both desks open filtered to NEW, so
     * a filtered count would leave two of the three permanently zero and destroy the one thing the
     * strip is read for, which is watching SUSPICIOUS rise while the work is done.
     *
     * A state with no alerts may be absent from the map rather than present at zero.
     */
    Map<FraudAlertState, Integer> countByState();
}
