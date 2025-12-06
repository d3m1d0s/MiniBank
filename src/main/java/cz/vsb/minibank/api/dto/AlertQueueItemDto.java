package cz.vsb.minibank.api.dto;

/**
 * Single item in the fraud analyst work queue.
 */
public record AlertQueueItemDto(
        int id,
        String alertCode,
        String transferCode,
        String state,
        String amount,
        String currency,
        String shortReason,
        String createdAt,
        Integer riskScore,
        String assignee
) {
}
