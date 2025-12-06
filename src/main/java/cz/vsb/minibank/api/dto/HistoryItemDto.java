package cz.vsb.minibank.api.dto;

/**
 * Single item in the payment history list.
 */
public record HistoryItemDto(
        int id,
        String createdAt,
        String amount,
        String currency,
        String status,
        String toIban,
        String declineReason
) {
}
