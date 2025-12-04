package cz.vsb.minibank.api.dto;

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
) {}
