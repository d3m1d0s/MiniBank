package cz.vsb.minibank.api.dto;

public record HistoryItemDto(
        int id,
        String createdAt,
        String amount,
        String currency,
        String status,
        String toIban,
        String declineReason
) {}
