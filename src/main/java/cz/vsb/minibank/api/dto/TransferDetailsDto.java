package cz.vsb.minibank.api.dto;

/**
 * Detailed view of a single transfer, including authorization metadata.
 */
public record TransferDetailsDto(
        int id,
        String fromIban,
        String fromBalance,
        String toIban,
        String amount,
        String feeAmount,
        String status,
        String createdAt,
        String authMethod,
        int triesLeft,
        String authValidUntil
) {
}
