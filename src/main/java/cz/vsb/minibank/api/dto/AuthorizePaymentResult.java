package cz.vsb.minibank.api.dto;

public record AuthorizePaymentResult(
        String status,
        String newBalance,
        String declineReason
) {}
