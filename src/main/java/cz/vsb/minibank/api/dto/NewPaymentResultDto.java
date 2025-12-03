package cz.vsb.minibank.api.dto;

public record NewPaymentResultDto(
        int transferId,
        String status,            // CREATED / SENT / WAITING_AUTH
        String chargedAmount,     // amount + fee, e.g. "1340.00 CZK"
        String feeAmount,         // fee alone, e.g. "0.00 CZK"
        String newBalance,        // account balance after operation
        boolean authorizationRequired
) {}
