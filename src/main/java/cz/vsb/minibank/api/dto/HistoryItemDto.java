package cz.vsb.minibank.api.dto;

import cz.vsb.minibank.domain.Transfer;

import java.util.function.Predicate;

/**
 * Single item in the payment history list.
 *
 * The account the money left stands before the account it went to, because a record's field order
 * is the key order of its JSON and a reader of the wire should be able to read the route in the
 * direction it happened. {@code fromIban} is the name {@link TransferInfoDto} already gives the
 * same fact; a second name for it would be one more thing the two desks could come to disagree on.
 *
 * Neither IBAN is ever null. Both lists that build this record resolve the source account from
 * rows they have already loaded, and both raise rather than pass a gap on: a history row that
 * cannot say which account it left is evidence with a hole in it, and the screens print it without
 * asking whether it is there.
 *
 * {@code toIbanInBank} stands beside the account it is about, and it is derived here rather than
 * sent raw. The fact has two sources that are not interchangeable, and a client handed both halves
 * would have to combine them itself, in the same way on both desks, which is the drift the shared
 * glossary and field list exist to prevent. {@link #isToIbanInBank} states the combination once.
 *
 * {@code fee} stands right after the amount it was added to, because that is where the screens
 * print it and the wire is read in the order of the meaning. It carries {@code Transfer.feeFor},
 * which is what was taken where the payment settled and what the current tariff would take where
 * it has not, and it is NEVER null: every row of every history table prints a fee line.
 *
 * That is a rule this record used to state the other way round, and the reason it was turned is
 * worth keeping. It used to carry {@code Transfer.fee}, the charge alone, so a payment that had
 * not settled arrived as null and its row drew no second line. On the demonstration data that is
 * three rows in twelve, and on an analyst's desk it is most of the table, because held and refused
 * payments are what a desk is for. A column that is filled on some rows and blank on others does
 * not read as two different answers; it reads as a table that lost some of its numbers, and the
 * customer cannot tell a payment that was charged nothing from one whose fee the screen dropped.
 *
 * What is given up is stated rather than hidden: on a refused payment the number is what the
 * payment WOULD have cost, not money anybody took. The status stands in the next cell and says
 * which of the two readings applies - {@code DECLINED} beside a fee is not a receipt - and that is
 * the whole of what separates them, so a screen that prints this field without the status beside
 * it would be printing a quote as a charge.
 *
 * The name is therefore no longer {@code feeCharged}. It stopped being only the charge, and a name
 * that says one rule over a field that follows another is exactly how the two came apart before.
 * It agrees with {@link TransferInfoDto#feeAmount} now, which has always answered {@code feeFor}.
 *
 * {@code settledAt} stands beside the instant it is measured against, and it is the fact this row
 * was missing rather than a convenience. {@code createdAt} is when the payment was asked for, which
 * is the only instant a row could print, so a payment submitted on Friday and sent on Tuesday read
 * as a Friday payment on every screen that shows this record. The two are not the same question and
 * on a fraud desk the difference is the whole point: how long a payment sat is what a held queue is
 * made of. Null on anything that has not settled, which is the honest answer and the one the shared
 * formatter already prints as a dash.
 *
 * {@code message} is the customer's own reference for the payment, and it travels for a reason that
 * is not symmetry: they could write one on the payment form and had nowhere to read it back. Beside
 * {@code declineReason} because the two are the sentences attached to a payment, the payer's and
 * the bank's, and a reader should meet them together.
 */
public record HistoryItemDto(
        int id,
        String createdAt,
        String settledAt,
        MoneyDto amount,
        MoneyDto fee,
        String status,
        String fromIban,
        String toIban,
        boolean toIbanInBank,
        String message,
        String declineReason
) {

    /**
     * Whether the account this payment names is one this bank holds.
     *
     * The rule lives on this record because this is the one of the three the two desks both build,
     * and the answer must be the same number of hops from the store on either of them.
     *
     * A recorded dispatch obligation is the one reading that can be trusted forever. {@code
     * Transfer.send} registers one exactly when the destination was not ours, so a dispatch state
     * that is present says the money left the bank, and it says so about the moment it left. That
     * reading has to stay a record: asking the store again would rewrite it the day an IBAN moved
     * to this bank, and a payment that went out over the network does not become an internal one
     * because its destination joined us afterwards.
     *
     * An ABSENT dispatch state is not the opposite reading, and treating it as one was a defect
     * that reached the screen. Null carries three different situations, which
     * {@link cz.vsb.minibank.domain.DispatchState} sets out and db/migrate/transfer-dispatch-state.sql
     * explains at length: an intra-bank payment, anything that has not settled, and every row
     * written before that column existed. The third is why a settled payment out of this bank can
     * hold a null, and on the demonstration database two of them do. The migration refuses to
     * backfill them, and refuses for a reason that is not going away: the rows it would mark are
     * ones the old code had already handed to the network, so marking them would have the dispatch
     * sweep send real payments a second time.
     *
     * So null means the record is silent, not that the answer is no, and a silent record is
     * answered by the live store. That answer is fresh by construction. What it costs is stated
     * rather than discovered: an intra-bank payment whose destination account is later closed
     * would begin reading as external. That is a narrower wrong than the one being fixed here,
     * which was every pre-migration external payment reading as internal.
     *
     * @param inBankNow whether this bank holds an IBAN at this moment, consulted whenever the
     *                  record is silent. A predicate rather than the repository, so a caller
     *                  drawing a page can answer a repeated IBAN from what it has already asked,
     *                  the way both list endpoints raise their IBAN map once for the page instead
     *                  of per row.
     */
    public static boolean isToIbanInBank(Transfer t, Predicate<String> inBankNow) {
        if (t.dispatchState() != null) {
            return false;
        }
        return inBankNow.test(t.targetIbanSnapshot());
    }
}
