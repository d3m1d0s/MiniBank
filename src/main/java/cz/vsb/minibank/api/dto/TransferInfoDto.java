package cz.vsb.minibank.api.dto;

/**
 * High-level transfer overview used in list or summary views.
 *
 * {@code toIbanInBank} stands beside the account it is about, and it is the same derived fact the
 * history rows beneath it carry, stated by {@link HistoryItemDto#isToIbanInBank}. Whether the money
 * left the bank is the first thing that decides what can still be done about a payment, so the desk
 * should not have to read it off the row below the one it is looking at.
 *
 * {@code dispatchState} stands beside it and not instead of it, exactly as it does on
 * {@link TransferDetailsDto}: the boolean says which side of this bank's edge the money was going,
 * this says how far it has got on the way out, and neither can be derived from the other. It is
 * here because this is the alerted payment, the one an analyst is deciding about, and the decision
 * turns on how far the money has travelled: PENDING is settled and queued for the network,
 * DISPATCHED is settled and handed over, and a payment already handed over is a case file rather
 * than something that can still be stopped. Null covers three situations at once and is not the
 * opposite answer, which is set out in {@link cz.vsb.minibank.domain.DispatchState}.
 *
 * {@code settledAt} and {@code message} complete the same thought and are the last two facts this
 * panel was missing. The alerted payment is the one record on the fraud desk that carried neither,
 * while the customer's own detail and every history row beneath this very panel carried both:
 * {@code settledAt} is when the money actually moved, which on a desk full of held payments is
 * days away from {@code createdAt} beside it, and {@code message} is the reference the customer
 * typed into the payment form. The message is the sharp one. It is counted against 140 characters
 * on the way in and stored, and it is often the whole of what the payer said about the payment, so
 * an analyst deciding that very payment was being asked to judge it with the payer's own
 * description hidden from them.
 *
 * {@code declineReason} stands immediately after it, and the pair is the rule {@link HistoryItemDto}
 * already states: the two sentences attached to a payment are the payer's and the bank's, and a
 * reader should meet them together. This record was the last of the three to be without it. The
 * shared field list has named it a fact this panel is obliged to carry since the list existed, and
 * every history row printed underneath this very panel was already printing it, so the one payment
 * on the desk whose refusal could not be read was the one the alert had been raised about. On the
 * demonstration data that is not hypothetical: the decided alert hangs off a payment declined with
 * "Withdrawn by the customer while the review was open", which is the sentence that says the case
 * ended without the bank stopping anything - and it was on the wire nowhere the desk could see it.
 *
 * Null while a payment might still go through, which is the same rule the other two records follow
 * and the reason the panel draws it only where there is text.
 */
public record TransferInfoDto(
        int id,
        String code,
        String status,
        String fromIban,
        MoneyDto fromBalance,
        String toIban,
        boolean toIbanInBank,
        String dispatchState,
        MoneyDto amount,
        MoneyDto feeAmount,
        String createdAt,
        String settledAt,
        String message,
        String declineReason,
        String authMethod
) {
}
