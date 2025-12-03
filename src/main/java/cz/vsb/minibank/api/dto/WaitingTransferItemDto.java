package cz.vsb.minibank.api.dto;

public record WaitingTransferItemDto(
        int id,
        String beneficiaryIban,
        String amount,
        String createdAt,
        String authMethod
) {}
