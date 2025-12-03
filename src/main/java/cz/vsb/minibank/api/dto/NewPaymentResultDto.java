package cz.vsb.minibank.api.dto;

public record NewPaymentResultDto(
        int transferId,
        String status,          // CREATED / SENT / WAITING_AUTH
        String amount,          // напр. "1340.00 CZK"
        String newBalance,      // баланс счёта после операции
        boolean authorizationRequired
) {}
