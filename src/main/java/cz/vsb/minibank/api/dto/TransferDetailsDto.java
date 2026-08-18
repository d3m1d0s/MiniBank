package cz.vsb.minibank.api.dto;

/**
 * Detailed view of a single transfer, including authorization metadata.
 *
 * @param feeAmount      what this transfer was charged once it has settled, and a quote from the
 *                       current fee policy until then - see Transfer.feeFor
 * @param settledAt      when the money moved, or null on a transfer that has not settled
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
        MoneyDto amount,
        MoneyDto feeAmount,
        String status,
        String createdAt,
        String settledAt,
        String message,
        String declineReason,
        String authMethod,
        Integer triesLeft,
        String authValidUntil
) {
}
