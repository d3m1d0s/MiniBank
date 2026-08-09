package cz.vsb.minibank.api.dto;

/**
 * Result of a new payment submission returned to the client UI.
 */
public record NewPaymentResultDto(
        int transferId,
        String status,            // SENT / WAITING_AUTH / HELD_FOR_REVIEW
        MoneyDto chargedAmount,   // amount + fee
        MoneyDto feeAmount,       // fee alone
        MoneyDto newBalance,      // account balance after the operation
        boolean authorizationRequired
) {
}
