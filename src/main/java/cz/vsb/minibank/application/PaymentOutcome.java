package cz.vsb.minibank.application;

import cz.vsb.minibank.domain.TransferStatus;
import cz.vsb.minibank.domain.value.Money;

/**
 * What a write operation did, read off the aggregates inside the unit of work that did it.
 *
 * Every field here is a fact as of that transaction, and that is the whole reason the type exists.
 * The three write endpoints used to call a service, let it commit and close its unit of work, and
 * only then read the transfer and the account back to build their response. Three things were
 * wrong with that and only the last one is obvious:
 *
 *   - The balance in the response was read after the commit, so another transaction could land in
 *     between and the customer was shown a number that was never the balance after their own
 *     payment. It was somebody else's.
 *   - The transfer and the account were two separate reads with nothing tying them together, so
 *     the status shown and the balance beside it could come from two different moments.
 *   - There is no connection pool, so each of those reads opened and tore down its own.
 *
 * What it does not claim is worth being exact about: {@code balance} is the balance **after this
 * operation**, not the balance now. That is what a receipt states, and it is the only phrasing
 * that stays true - a later read would be neither.
 *
 * An application-layer record rather than the API DTO, so the service layer states facts and the
 * controller decides how to show them; and rather than the aggregates themselves, because a live
 * {@code Account} handed out of a closed unit of work is a mutable object nobody owns.
 *
 * @param fee           what this transfer was charged if it has settled, and a quote from the
 *                      current policy until then - see {@code Transfer.feeFor}
 * @param declineReason why the transfer was declined, or null when it was not
 */
public record PaymentOutcome(
        int transferId,
        TransferStatus status,
        Money amount,
        Money fee,
        Money balance,
        String declineReason
) {
}
