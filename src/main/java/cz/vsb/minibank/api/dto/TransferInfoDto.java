package cz.vsb.minibank.api.dto;

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
) {}
