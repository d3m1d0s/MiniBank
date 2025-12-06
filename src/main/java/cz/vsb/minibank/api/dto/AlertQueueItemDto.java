package cz.vsb.minibank.api.dto;

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
) {}
