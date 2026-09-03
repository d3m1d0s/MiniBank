package cz.vsb.minibank.api.dto.fraud;
import cz.vsb.minibank.api.dto.common.MoneyDto;

/**
 * Single item in the fraud analyst work queue.
 */
public record AlertQueueItemDto(
        int id,
        String alertCode,
        String transferCode,
        String state,
        // The transfer's status, not the alert's. The queue used to show only the alert state,
        // so a payment that had already gone looked identical to one still held for review -
        // half of why the fraud feature gated nothing.
        String transferStatus,
        MoneyDto amount,
        String shortReason,
        String createdAt,
        Integer riskScore,
        String assignee
) {
}
