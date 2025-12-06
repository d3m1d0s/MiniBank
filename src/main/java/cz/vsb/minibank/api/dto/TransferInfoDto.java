package cz.vsb.minibank.api.dto;

/**
 * High-level transfer overview used in list or summary views.
 */
public record TransferInfoDto(
        int id,
        String code,
        String status,
        String fromIban,
        String fromBalance,
        String toIban,
        String amount,
        String feeAmount,
        String currency,
        String createdAt,
        String authMethod
) {
}
