package cz.vsb.minibank.api.dto;

/**
 * High-level transfer overview used in list or summary views.
 */
public record TransferInfoDto(
        int id,
        String code,
        String status,
        String fromIban,
        MoneyDto fromBalance,
        String toIban,
        MoneyDto amount,
        MoneyDto feeAmount,
        String createdAt,
        String authMethod
) {
}
