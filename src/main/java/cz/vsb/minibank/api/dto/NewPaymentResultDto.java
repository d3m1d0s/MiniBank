package cz.vsb.minibank.api.dto;

/**
 * Result of a new payment submission returned to the client UI.
 */
public record NewPaymentResultDto(
        int transferId,
        String status,            // CREATED / SENT / WAITING_AUTH
        String chargedAmount,     // amount + fee, for example "1340.00 CZK"
        String feeAmount,         // fee alone, for example "0.00 CZK"
        String newBalance,        // account balance after the operation
        boolean authorizationRequired
) {
}
