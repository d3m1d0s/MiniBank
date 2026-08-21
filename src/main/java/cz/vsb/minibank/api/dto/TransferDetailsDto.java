package cz.vsb.minibank.api.dto;

/**
 * Detailed view of a single transfer, including authorization metadata.
 *
 * @param toIbanInBank   whether the account this payment names is one this bank holds, which is
 *                       what decides whether the money stayed inside or was owed to the payment
 *                       network. Derived rather than raw, and stated once for all three records
 *                       that carry it - see {@link HistoryItemDto#isToIbanInBank}.
 * @param feeAmount      what this transfer was charged once it has settled, and a quote from the
 *                       current fee policy until then - see Transfer.feeFor
 * @param settledAt      when the money moved, or null on a transfer that has not settled
 * @param dispatchState  what this payment still owes the payment network: PENDING once it has
 *                       settled out of this bank and no gateway has been handed it, DISPATCHED
 *                       once one has, and null when it owes nothing. Null is the common answer
 *                       and covers three cases at once - an intra-bank payment, anything that has
 *                       not settled, and every row written before the column existed - so it is
 *                       NOT the opposite of the two named states and a screen must not read it as
 *                       "stayed in the bank". {@code toIbanInBank} above is the field that answers
 *                       that, and it is derived rather than left for a client to combine; this one
 *                       says how far a payment that is leaving has got on the way out, which is
 *                       the only fact on this record that no other field carries
 * @param message        the customer's own reference, or null when they gave none. Accepted on the
 *                       creation request since long before it was stored; this is the first time it
 *                       can be read back.
 * @param declineReason  why the payment was stopped, or null while it still might go through. The
 *                       analyst could already read this in the alert history of the very same
 *                       transfer; its owner could not read it anywhere.
 * @param triesLeft      how many one time codes are left, or null when the transfer is not
 *                       WAITING_AUTH. A held transfer reported three attempts beside a Confirm
 *                       button that cannot be pressed, which is a number about a step the customer
 *                       has not been offered yet.
 * @param authValidUntil the deadline for those codes, null for the same reason and on the same
 *                       statuses. Transfer clears the underlying instant on hold and on release,
 *                       so this is the read side of a rule the domain already keeps.
 */
public record TransferDetailsDto(
        int id,
        String fromIban,
        MoneyDto fromBalance,
        String toIban,
        boolean toIbanInBank,
        MoneyDto amount,
        MoneyDto feeAmount,
        String status,
        String createdAt,
        String settledAt,
        String dispatchState,
        String message,
        String declineReason,
        // Null, never an empty string, on every producer of this field. See
        // AuthorizationController.authMethodOf for the three shapes it used to arrive in.
        String authMethod,
        Integer triesLeft,
        String authValidUntil
) {
}
