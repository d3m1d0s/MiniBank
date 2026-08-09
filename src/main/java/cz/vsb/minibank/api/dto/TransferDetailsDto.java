package cz.vsb.minibank.api.dto;

/**
 * Detailed view of a single transfer, including authorization metadata.
 *
 * @param feeAmount what this transfer was charged once it has settled, and a quote from the
 *                  current fee policy until then - see Transfer.feeFor
 * @param settledAt when the money moved, or null on a transfer that has not settled
 * @param message   the customer's own reference, or null when they gave none. Accepted on the
 *                  creation request since long before it was stored; this is the first time it
 *                  can be read back.
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
        String authMethod,
        int triesLeft,
        String authValidUntil
) {
}
